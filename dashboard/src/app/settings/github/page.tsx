import { GithubIntegrationCard } from '@/components/ui/github-integration-card';

export default function GithubSettingsPage() {
  return (
    <div className="flex-1 flex flex-col items-center p-6 overflow-auto">
      <div className="w-full max-w-4xl space-y-6">
        <header>
          <h1 className="text-xl font-semibold text-white">GitHub</h1>
          <p className="mt-1 text-sm text-white/50">
            Connect a GitHub account so mission agents can commit, push, and use the gh CLI
          </p>
        </header>

        <GithubIntegrationCard />
      </div>
    </div>
  );
}
