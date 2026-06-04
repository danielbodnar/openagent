//! Minimal OpenAI-compatible chat client with tool-calling support for the Ask
//! assistant loop. Reuses the metadata LLM config (base URL / key / model) but
//! adds function/tool calling, which the metadata summarizer does not need.
//!
//! Cerebras (and most fast providers) expose the OpenAI `/chat/completions`
//! shape, so this client targets that format only.

use serde_json::{json, Value};

use crate::api::metadata_llm::{ApiFormat, MetadataLlmConfig};

/// Whether a model is a reasoning model that needs an explicit `reasoning_effort`
/// (and token headroom) to return visible content on the OpenAI-compatible API.
fn model_is_reasoning(model: &str) -> bool {
    let m = model.to_lowercase();
    // Match only actual reasoning variants — not every Qwen model (e.g.
    // qwen2.5-coder is NOT a reasoning model and rejects `reasoning_effort`).
    m.contains("gpt-oss")
        || m.contains("qwen3")
        || m.contains("qwq")
        || m.contains("thinking")
        || m.contains("reasoning")
        // Cerebras's GLM 4.7 (`zai-glm-4.7`) is a reasoning model: without an
        // explicit effort it spends the budget on hidden reasoning and returns
        // empty content.
        || m.contains("glm")
        || m.contains("zai")
}

/// A tool call requested by the model.
#[derive(Debug, Clone)]
pub struct ToolCall {
    pub id: String,
    pub name: String,
    /// Raw JSON arguments string (as returned by the model).
    pub arguments: String,
}

/// One assistant turn returned by the model.
#[derive(Debug, Clone, Default)]
pub struct AskCompletion {
    pub content: Option<String>,
    pub tool_calls: Vec<ToolCall>,
    /// Total tokens reported by the provider for this call (prompt + completion).
    pub total_tokens: Option<u64>,
}

/// Thin chat client bound to a resolved LLM config.
pub struct AskClient {
    http: reqwest::Client,
    config: MetadataLlmConfig,
}

impl AskClient {
    pub fn new(http: reqwest::Client, config: MetadataLlmConfig) -> Self {
        Self { http, config }
    }

    pub fn model(&self) -> &str {
        &self.config.model
    }

    /// Run one chat completion.
    ///
    /// `messages` is an OpenAI-style message array (already including system,
    /// prior turns, and tool results). `tools` is an OpenAI-style tool array; an
    /// empty slice disables tool calling.
    pub async fn complete(
        &self,
        messages: &[Value],
        tools: &[Value],
    ) -> Result<AskCompletion, String> {
        if self.config.api_format != ApiFormat::OpenAI {
            return Err("Ask assistant requires an OpenAI-compatible provider".to_string());
        }

        let url = format!(
            "{}/chat/completions",
            self.config.base_url.trim_end_matches('/')
        );

        let mut body = json!({
            "model": self.config.model,
            "messages": messages,
            "temperature": 0.3,
            "max_tokens": 2048,
        });
        if !tools.is_empty() {
            body["tools"] = json!(tools);
            body["tool_choice"] = json!("auto");
        }
        // gpt-oss / qwen3 on Cerebras are reasoning models: without an explicit
        // effort (and token headroom) they spend the budget on hidden reasoning
        // and return empty content. "low" keeps the sidecar snappy.
        if model_is_reasoning(&self.config.model) {
            body["reasoning_effort"] = json!("low");
        }

        let resp = self
            .http
            .post(&url)
            .header("Content-Type", "application/json")
            .header("Authorization", format!("Bearer {}", self.config.api_key))
            .timeout(std::time::Duration::from_secs(60))
            .json(&body)
            .send()
            .await
            .map_err(|e| format!("Ask LLM request error: {e}"))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let text = resp.text().await.unwrap_or_default();
            return Err(format!("Ask LLM returned {status}: {text}"));
        }

        let json: Value = resp
            .json()
            .await
            .map_err(|e| format!("Ask LLM parse error: {e}"))?;

        let message = &json["choices"][0]["message"];
        let content = message["content"].as_str().and_then(|s| {
            let t = s.trim();
            if t.is_empty() {
                None
            } else {
                Some(t.to_string())
            }
        });

        let mut tool_calls = Vec::new();
        if let Some(calls) = message["tool_calls"].as_array() {
            for call in calls {
                let id = call["id"].as_str().unwrap_or("").to_string();
                let name = call["function"]["name"].as_str().unwrap_or("").to_string();
                let arguments = call["function"]["arguments"]
                    .as_str()
                    .unwrap_or("{}")
                    .to_string();
                if !name.is_empty() {
                    tool_calls.push(ToolCall {
                        id,
                        name,
                        arguments,
                    });
                }
            }
        }

        let total_tokens = json["usage"]["total_tokens"].as_u64();

        Ok(AskCompletion {
            content,
            tool_calls,
            total_tokens,
        })
    }

    /// Streaming variant of [`complete`]. Calls `on_delta` with each token
    /// fragment of the assistant's visible content as it arrives, and returns
    /// the fully-assembled completion (content + tool calls) at the end.
    pub async fn complete_stream<F: FnMut(&str)>(
        &self,
        messages: &[Value],
        tools: &[Value],
        mut on_delta: F,
    ) -> Result<AskCompletion, String> {
        use futures::StreamExt;

        if self.config.api_format != ApiFormat::OpenAI {
            return Err("Ask assistant requires an OpenAI-compatible provider".to_string());
        }

        let url = format!(
            "{}/chat/completions",
            self.config.base_url.trim_end_matches('/')
        );
        let mut body = json!({
            "model": self.config.model,
            "messages": messages,
            "temperature": 0.3,
            "max_tokens": 2048,
            "stream": true,
        });
        // `stream_options.include_usage` is an OpenAI extension Cerebras supports
        // to emit a final usage frame. Some other OpenAI-compatible providers
        // reject unknown fields, which would make `/ask/stream` fail even when
        // synchronous `/ask` works, so only request it for Cerebras. The
        // non-streaming `complete` path reads usage from the response body and
        // never needs this field.
        if self.config.base_url.contains("cerebras") {
            body["stream_options"] = json!({ "include_usage": true });
        }
        if !tools.is_empty() {
            body["tools"] = json!(tools);
            body["tool_choice"] = json!("auto");
        }
        if model_is_reasoning(&self.config.model) {
            body["reasoning_effort"] = json!("low");
        }

        let resp = self
            .http
            .post(&url)
            .header("Content-Type", "application/json")
            .header("Authorization", format!("Bearer {}", self.config.api_key))
            .timeout(std::time::Duration::from_secs(120))
            .json(&body)
            .send()
            .await
            .map_err(|e| format!("Ask LLM request error: {e}"))?;
        if !resp.status().is_success() {
            let status = resp.status();
            let text = resp.text().await.unwrap_or_default();
            return Err(format!("Ask LLM returned {status}: {text}"));
        }

        let mut stream = resp.bytes_stream();
        // Buffer raw bytes and only decode complete lines: a multibyte UTF-8
        // char split across chunk boundaries must not be lossily decoded mid-way.
        let mut buf: Vec<u8> = Vec::new();
        let mut content = String::new();
        // Tool calls arrive as fragments keyed by index; accumulate (id,name,args).
        let mut tool_accum: Vec<(String, String, String)> = Vec::new();
        let mut total_tokens: Option<u64> = None;

        let mut ended = false;
        'outer: while !ended {
            match stream.next().await {
                Some(chunk) => {
                    let bytes = chunk.map_err(|e| format!("Ask LLM stream error: {e}"))?;
                    buf.extend_from_slice(&bytes);
                }
                None => {
                    // Connection closed: flush a trailing line that lacks a
                    // newline so a final `data:` chunk isn't dropped.
                    ended = true;
                    if !buf.is_empty() && buf.last() != Some(&b'\n') {
                        buf.push(b'\n');
                    }
                }
            }
            while let Some(nl) = buf.iter().position(|&b| b == b'\n') {
                let line_bytes: Vec<u8> = buf.drain(..=nl).collect();
                // A complete line ends at a single-byte '\n', so decoding it
                // can't split a multibyte char.
                let line = String::from_utf8_lossy(&line_bytes);
                let line = line.trim();
                let Some(data) = line.strip_prefix("data: ") else {
                    continue;
                };
                if data == "[DONE]" {
                    break 'outer;
                }
                let Ok(json) = serde_json::from_str::<Value>(data) else {
                    continue;
                };
                if let Some(t) = json["usage"]["total_tokens"].as_u64() {
                    total_tokens = Some(t);
                }
                let delta = &json["choices"][0]["delta"];
                if let Some(c) = delta["content"].as_str() {
                    if !c.is_empty() {
                        content.push_str(c);
                        on_delta(c);
                    }
                }
                if let Some(tcs) = delta["tool_calls"].as_array() {
                    for tc in tcs {
                        let idx = tc["index"].as_u64().unwrap_or(0) as usize;
                        while tool_accum.len() <= idx {
                            tool_accum.push((String::new(), String::new(), String::new()));
                        }
                        let entry = &mut tool_accum[idx];
                        if let Some(id) = tc["id"].as_str() {
                            if !id.is_empty() {
                                entry.0 = id.to_string();
                            }
                        }
                        if let Some(n) = tc["function"]["name"].as_str() {
                            if !n.is_empty() {
                                entry.1 = n.to_string();
                            }
                        }
                        if let Some(a) = tc["function"]["arguments"].as_str() {
                            entry.2.push_str(a);
                        }
                    }
                }
            }
        }

        let tool_calls = tool_accum
            .into_iter()
            .filter(|(_, name, _)| !name.is_empty())
            .map(|(id, name, arguments)| ToolCall {
                id,
                name,
                arguments,
            })
            .collect();

        Ok(AskCompletion {
            content: if content.trim().is_empty() {
                None
            } else {
                Some(content)
            },
            tool_calls,
            total_tokens,
        })
    }
}
