export default function SettingsLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="h-[calc(100vh-3rem)] lg:h-screen flex flex-col overflow-hidden">
      {children}
    </div>
  );
}
