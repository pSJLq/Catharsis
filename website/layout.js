import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const dir = path.join(path.dirname(fileURLToPath(import.meta.url)), 'partials');

export function applyLayout(html) {
  const header = fs.readFileSync(path.join(dir, 'header.html'), 'utf8');
  const footer = fs.readFileSync(path.join(dir, 'footer.html'), 'utf8');
  return html.replaceAll('<!--layout:header-->', header).replaceAll('<!--layout:footer-->', footer);
}
