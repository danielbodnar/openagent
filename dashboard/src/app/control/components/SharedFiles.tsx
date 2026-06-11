"use client";

// Shared-file rendering for the control page (inline images, file cards, and
// the full-page text/markdown preview modal). Extracted mechanically from
// control-client.tsx.

import { useCallback, useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import {
  Check,
  Code,
  Copy,
  Download,
  ExternalLink,
  Eye,
  File,
  FileArchive,
  FileText,
  Image as ImageIcon,
  X,
} from "lucide-react";
import { MarkdownContent } from "@/components/markdown-content";
import { LazyCodeBlock } from "@/components/lazy-code-block";
import { cn } from "@/lib/utils";
import { authHeader } from "@/lib/auth";
import { getRuntimeApiBase } from "@/lib/settings";
import { formatBytes, type SharedFile } from "@/lib/api";
import { Shimmer } from "./common";

function isTextPreviewableSharedFile(file: SharedFile): boolean {
  const name = (file.name || "").toLowerCase();
  if (file.content_type.startsWith("text/")) return true;
  if (
    file.content_type.includes("json") ||
    file.content_type.includes("yaml") ||
    file.content_type.includes("xml")
  ) {
    return true;
  }
  return (
    name.endsWith(".txt") ||
    name.endsWith(".md") ||
    name.endsWith(".markdown") ||
    name.endsWith(".log") ||
    name.endsWith(".json") ||
    name.endsWith(".yaml") ||
    name.endsWith(".yml") ||
    name.endsWith(".toml") ||
    name.endsWith(".xml") ||
    name.endsWith(".csv") ||
    name.endsWith(".tsv")
  );
}

function getLanguageFromSharedFile(file: SharedFile): string {
  const name = (file.name || "").toLowerCase();
  if (
    name.endsWith(".md") ||
    name.endsWith(".markdown") ||
    file.content_type.includes("markdown")
  )
    return "markdown";
  if (name.endsWith(".json") || file.content_type.includes("json"))
    return "json";
  if (
    name.endsWith(".yaml") ||
    name.endsWith(".yml") ||
    file.content_type.includes("yaml")
  )
    return "yaml";
  if (name.endsWith(".xml") || file.content_type.includes("xml")) return "xml";
  if (name.endsWith(".csv")) return "csv";
  if (name.endsWith(".tsv")) return "tsv";
  return "text";
}

function SharedFilePreviewModal({
  file,
  resolvedUrl,
  isApiUrl,
  onClose,
  onDownload,
}: {
  file: SharedFile;
  resolvedUrl: string;
  isApiUrl: boolean;
  onClose: () => void;
  onDownload: () => void;
}) {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [text, setText] = useState<string>("");
  const [copied, setCopied] = useState(false);
  const [sizeBytes, setSizeBytes] = useState<number | null>(null);

  const language = useMemo(() => getLanguageFromSharedFile(file), [file]);
  const isMarkdown = language === "markdown";

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      setLoading(true);
      setError(null);
      setText("");
      setSizeBytes(null);
      try {
        const res = await fetch(resolvedUrl, {
          headers: isApiUrl ? { ...authHeader() } : undefined,
        });
        if (!res.ok) throw new Error(`Failed to load (${res.status})`);
        const blob = await res.blob();
        const raw = await blob.text();
        const limit = 500_000;
        const finalText =
          raw.length > limit
            ? `${raw.slice(0, limit)}\n\n... (file truncated, too large to preview)`
            : raw;
        if (!cancelled) {
          setSizeBytes(blob.size);
          setText(finalText);
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void run();
    return () => {
      cancelled = true;
    };
  }, [isApiUrl, resolvedUrl]);

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Ignore.
    }
  }, [text]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 md:p-8"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="absolute inset-0 bg-black/70 backdrop-blur-sm pointer-events-none" />
      <div
        onClick={(e) => e.stopPropagation()}
        className={cn(
          // Full-page reader (viewport minus padding) so the document clearly
          // sits over the page instead of reading as a chat-width expansion.
          "relative flex h-full w-full max-w-6xl flex-col rounded-2xl bg-[#1a1a1a] border border-white/[0.06] shadow-xl",
          "animate-in fade-in zoom-in-95 duration-200",
        )}
      >
        <div className="flex items-center justify-between px-5 py-4 border-b border-white/[0.06]">
          <div className="min-w-0">
            <h3 className="text-sm font-semibold text-white truncate">
              {file.name}
            </h3>
            <p className="text-xs text-white/40 truncate">
              {file.content_type}
              {sizeBytes != null && (
                <span className="ml-2">• {formatBytes(sizeBytes)}</span>
              )}
            </p>
          </div>
          <div className="flex items-center gap-2 shrink-0 ml-3">
            {!loading && !error && text && (
              <button
                onClick={handleCopy}
                className="p-1.5 rounded-lg text-white/40 hover:text-white/70 hover:bg-white/[0.08] transition-colors"
                title={copied ? "Copied" : "Copy"}
              >
                {copied ? (
                  <Check className="h-4 w-4 text-emerald-400" />
                ) : (
                  <Copy className="h-4 w-4" />
                )}
              </button>
            )}
            <button
              onClick={onDownload}
              className="p-1.5 rounded-lg text-white/40 hover:text-white/70 hover:bg-white/[0.08] transition-colors"
              title="Download"
            >
              <Download className="h-4 w-4" />
            </button>
            <button
              onClick={onClose}
              className="p-1.5 rounded-lg text-white/40 hover:text-white/70 hover:bg-white/[0.08] transition-colors"
              title="Close"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        </div>

        <div className="min-h-0 flex-1 overflow-auto">
          {loading ? (
            <div className="p-5">
              <Shimmer />
            </div>
          ) : error ? (
            <div className="p-5 text-sm text-red-400">{error}</div>
          ) : isMarkdown ? (
            <div className="p-5">
              <MarkdownContent content={text} />
            </div>
          ) : (
            <div className="text-sm">
              <LazyCodeBlock
                language={language}
                showLineNumbers
                customStyle={{
                  padding: "1rem",
                  background: "transparent",
                  fontSize: "0.8125rem",
                }}
              >
                {text}
              </LazyCodeBlock>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// Shared file card component - renders images inline and other files as download cards
export function SharedFileCard({ file }: { file: SharedFile }) {
  const iconMap: Record<SharedFile["kind"], typeof File> = {
    image: ImageIcon,
    document: FileText,
    archive: FileArchive,
    code: Code,
    other: File,
  };
  const FileIcon = iconMap[file.kind] || File;

  // Format file size
  const sizeLabel = file.size_bytes ? formatBytes(file.size_bytes) : null;

  const apiBase = getRuntimeApiBase();
  const isApiRelativeUrl = file.url.startsWith("/");
  const isApiUrl = isApiRelativeUrl || file.url.startsWith(apiBase);
  const resolvedUrl = isApiRelativeUrl ? `${apiBase}${file.url}` : file.url;
  const canPreview = isTextPreviewableSharedFile(file);

  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);

  // If this is an API-protected image, fetch it with auth and render from an object URL.
  useEffect(() => {
    if (file.kind !== "image") return;
    if (!isApiUrl) return; // External URLs can be loaded directly by the browser.

    let cancelled = false;
    let localUrl: string | null = null;

    const run = async () => {
      setLoading(true);
      setError(null);
      try {
        const res = await fetch(resolvedUrl, { headers: { ...authHeader() } });
        if (!res.ok) throw new Error(`Failed to load image (${res.status})`);
        const blob = await res.blob();
        localUrl = URL.createObjectURL(blob);
        if (!cancelled) setBlobUrl(localUrl);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    void run();
    return () => {
      cancelled = true;
      if (localUrl) URL.revokeObjectURL(localUrl);
    };
  }, [file.kind, isApiUrl, resolvedUrl]);

  const handleDownload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // If URL is external, let the browser handle it.
      if (!isApiUrl) {
        window.open(resolvedUrl, "_blank", "noopener,noreferrer");
        return;
      }

      const res = await fetch(resolvedUrl, { headers: { ...authHeader() } });
      if (!res.ok) throw new Error(`Download failed (${res.status})`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      try {
        const a = document.createElement("a");
        a.href = url;
        a.download = file.name || "download";
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
      } finally {
        URL.revokeObjectURL(url);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [file.name, isApiUrl, resolvedUrl]);

  const handleOpen = useCallback(() => {
    if (file.kind === "image" && blobUrl) {
      window.open(blobUrl, "_blank", "noopener,noreferrer");
      return;
    }
    if (!isApiUrl) {
      window.open(resolvedUrl, "_blank", "noopener,noreferrer");
      return;
    }
    // For API URLs we can't open directly without headers; download instead.
    void handleDownload();
  }, [blobUrl, file.kind, handleDownload, isApiUrl, resolvedUrl]);

  if (file.kind === "image") {
    // Render images inline (supports auth-protected API URLs).
    return (
      <div className="mt-3 rounded-lg overflow-hidden border border-white/[0.06] bg-black/20">
        <button
          type="button"
          onClick={handleOpen}
          className="block w-full text-left"
        >
          {loading && !blobUrl ? (
            <div className="h-[240px] w-full animate-pulse bg-white/[0.03]" />
          ) : (
            <>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={blobUrl || resolvedUrl}
                alt={file.name}
                className="max-w-full max-h-[400px] object-contain"
                loading="lazy"
              />
            </>
          )}
        </button>
        <div className="flex items-center gap-2 px-3 py-2 text-xs text-white/40 border-t border-white/[0.06]">
          <ImageIcon aria-hidden="true" className="h-3 w-3" />
          <span className="truncate flex-1">{file.name}</span>
          {sizeLabel && <span>{sizeLabel}</span>}
          <button
            type="button"
            onClick={handleOpen}
            className="text-indigo-400 hover:text-indigo-300 flex items-center gap-1"
            title="Open"
            aria-label="Open"
          >
            <ExternalLink className="h-3 w-3" />
          </button>
          <button
            type="button"
            onClick={handleDownload}
            className="text-indigo-400 hover:text-indigo-300 flex items-center gap-1"
            title="Download"
            aria-label="Download"
            disabled={loading}
          >
            <Download className={cn("h-3 w-3", loading && "animate-pulse")} />
          </button>
        </div>
        {error && <div className="px-3 pb-2 text-xs text-red-400">{error}</div>}
      </div>
    );
  }

  // Render other files as cards (download always, preview for text/markdown)
  return (
    <>
      <div
        className={cn(
          "mt-3 flex items-center gap-3 px-4 py-3 rounded-lg border border-white/[0.06] bg-white/[0.02] hover:bg-white/[0.04] transition-colors group",
          canPreview && "cursor-pointer",
        )}
        onClick={() => {
          if (canPreview) setPreviewOpen(true);
        }}
        role={canPreview ? "button" : undefined}
        tabIndex={canPreview ? 0 : undefined}
        onKeyDown={(e) => {
          if (!canPreview) return;
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            setPreviewOpen(true);
          }
        }}
      >
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-indigo-500/10">
          <FileIcon className="h-5 w-5 text-indigo-400" />
        </div>
        <div className="flex-1 min-w-0">
          <div className="font-medium text-sm text-white/80 truncate">
            {file.name}
          </div>
          <div className="text-xs text-white/40 flex items-center gap-2">
            <span className="truncate">{file.content_type}</span>
            {sizeLabel && (
              <>
                <span>•</span>
                <span>{sizeLabel}</span>
              </>
            )}
          </div>
          {error && <div className="mt-1 text-xs text-red-400">{error}</div>}
        </div>

        {canPreview && (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              setPreviewOpen(true);
            }}
            className="p-2 rounded-md text-white/30 group-hover:text-indigo-400 hover:bg-white/[0.06] transition-colors"
            title="Preview"
            aria-label="Preview"
            disabled={loading}
          >
            <Eye className={cn("h-4 w-4", loading && "animate-pulse")} />
          </button>
        )}

        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            void handleDownload();
          }}
          className="p-2 rounded-md text-white/30 group-hover:text-indigo-400 hover:bg-white/[0.06] transition-colors"
          title="Download"
          aria-label="Download"
          disabled={loading}
        >
          <Download className={cn("h-4 w-4", loading && "animate-pulse")} />
        </button>
      </div>

      {/* Portal to body: the card lives inside a virtualized row positioned
          with transform:translateY, which would otherwise trap the modal's
          position:fixed and render it inside the conversation. */}
      {previewOpen &&
        canPreview &&
        typeof document !== "undefined" &&
        createPortal(
          <SharedFilePreviewModal
            file={file}
            resolvedUrl={resolvedUrl}
            isApiUrl={isApiUrl}
            onClose={() => setPreviewOpen(false)}
            onDownload={() => void handleDownload()}
          />,
          document.body,
        )}
    </>
  );
}
