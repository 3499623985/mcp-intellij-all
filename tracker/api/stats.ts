import { neon } from '@neondatabase/serverless';
import type { VercelRequest, VercelResponse } from '@vercel/node';

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method !== 'GET') return res.status(405).end();

  const sql = neon(process.env.POSTGRES_URL!);

  const rows = await sql`
    SELECT tool_name, COUNT(*)::int AS cnt
    FROM events
    GROUP BY tool_name
    ORDER BY cnt DESC
  `;

  const stats: Record<string, number> = {};
  for (const row of rows) {
    stats[row.tool_name] = row.cnt;
  }

  res.setHeader('Cache-Control', 'no-store');
  return res.status(200).json(stats);
}
