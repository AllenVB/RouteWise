export function Badge({ text, tone }: { text: string; tone?: string }) {
  return <span className={`badge ${tone ?? ''}`}>{text}</span>;
}
