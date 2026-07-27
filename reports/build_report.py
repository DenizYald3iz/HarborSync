#!/usr/bin/env python3
from __future__ import annotations

import html
import re
import subprocess
import sys
import unicodedata
from pathlib import Path


ROOT = Path(__file__).resolve().parent
MARKDOWN = ROOT / "harborsync_analysis_report.md"
HTML = ROOT / "harborsync_analysis_report.html"
PDF = ROOT / "harborsync_analysis_report.pdf"


def inline_markup(text: str) -> str:
    escaped = html.escape(text)
    escaped = re.sub(r"`([^`]+)`", r"<code>\1</code>", escaped)
    escaped = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", escaped)
    return escaped


def slugify(text: str, used: set[str]) -> str:
    normalized = unicodedata.normalize("NFKD", text)
    ascii_text = normalized.encode("ascii", "ignore").decode("ascii").lower()
    slug = re.sub(r"[^a-z0-9]+", "-", ascii_text).strip("-") or "section"
    original = slug
    counter = 2
    while slug in used:
        slug = f"{original}-{counter}"
        counter += 1
    used.add(slug)
    return slug


def collect_toc(markdown_text: str) -> tuple[list[tuple[int, str, str]], dict[str, str]]:
    used: set[str] = set()
    toc: list[tuple[int, str, str]] = []
    heading_ids: dict[str, str] = {}
    heading_index = 0

    for line in markdown_text.splitlines():
        match = re.match(r"^(#{1,3})\s+(.*)$", line.strip())
        if not match:
            continue
        level = len(match.group(1))
        title = match.group(2).strip()
        slug = slugify(title, used)
        heading_ids[f"{level}:{title}:{heading_index}"] = slug
        heading_index += 1
        if level in (2, 3):
            toc.append((level, title, slug))

    return toc, heading_ids


def render_toc(toc: list[tuple[int, str, str]]) -> str:
    out = ['<section class="toc page-break">', '<h2>İçindekiler</h2>', '<ul class="toc-list">']
    for level, title, slug in toc:
        css_class = "toc-h3" if level == 3 else "toc-h2"
        out.append(f'<li class="{css_class}"><a href="#{slug}">{inline_markup(title)}</a></li>')
    out.extend(['</ul>', '</section>'])
    return "\n".join(out)


def render_table(lines: list[str]) -> str:
    rows = []
    for line in lines:
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells):
            continue
        rows.append(cells)

    if not rows:
        return ""

    out = ["<table>"]
    header, *body = rows
    out.append("<thead><tr>")
    for cell in header:
        out.append(f"<th>{inline_markup(cell)}</th>")
    out.append("</tr></thead>")
    if body:
        out.append("<tbody>")
        for row in body:
            out.append("<tr>")
            for cell in row:
                out.append(f"<td>{inline_markup(cell)}</td>")
            out.append("</tr>")
        out.append("</tbody>")
    out.append("</table>")
    return "\n".join(out)


def render_markdown(markdown_text: str, heading_ids: dict[str, str]) -> str:
    lines = markdown_text.splitlines()
    out: list[str] = []
    paragraph: list[str] = []
    list_items: list[str] = []
    heading_index = 0
    i = 0

    def flush_paragraph() -> None:
        if paragraph:
            out.append("<p>" + inline_markup(" ".join(paragraph).strip()) + "</p>")
            paragraph.clear()

    def flush_list() -> None:
        if list_items:
            out.append("<ul>")
            for item in list_items:
                out.append(f"<li>{inline_markup(item)}</li>")
            out.append("</ul>")
            list_items.clear()

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if stripped.startswith("```"):
            flush_paragraph()
            flush_list()
            i += 1
            code_lines = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code_lines.append(lines[i])
                i += 1
            out.append("<pre><code>" + html.escape("\n".join(code_lines)) + "</code></pre>")
            i += 1
            continue

        if not stripped:
            flush_paragraph()
            flush_list()
            i += 1
            continue

        if stripped.startswith("|") and i + 1 < len(lines) and lines[i + 1].strip().startswith("|"):
            flush_paragraph()
            flush_list()
            table_lines = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                table_lines.append(lines[i])
                i += 1
            out.append(render_table(table_lines))
            continue

        if stripped.startswith("Şekil ") or stripped.startswith("Sekil "):
            flush_paragraph()
            flush_list()
            out.append(f'<p class="figure-caption">{inline_markup(stripped)}</p>')
            i += 1
            continue

        heading = re.match(r"^(#{1,4})\s+(.*)$", stripped)
        if heading:
            flush_paragraph()
            flush_list()
            level = len(heading.group(1))
            title = heading.group(2).strip()
            key = f"{level}:{title}:{heading_index}"
            heading_index += 1
            heading_id = heading_ids.get(key)
            attr = f' id="{heading_id}"' if heading_id else ""
            out.append(f"<h{level}{attr}>{inline_markup(title)}</h{level}>")
            i += 1
            continue

        bullet = re.match(r"^[-*]\s+(.*)$", stripped)
        numbered = re.match(r"^\d+\.\s+(.*)$", stripped)
        if bullet or numbered:
            flush_paragraph()
            list_items.append((bullet or numbered).group(1))
            i += 1
            continue

        flush_list()
        paragraph.append(stripped)
        i += 1

    flush_paragraph()
    flush_list()
    return "\n".join(out)


def build_html(body: str) -> str:
    return f"""<!doctype html>
<html lang="tr">
<head>
  <meta charset="utf-8">
  <title>HarborSync Mikroservis Analizi</title>
  <style>
    @page {{ size: A4; margin: 16mm 14mm; }}
    body {{
      font-family: "Noto Sans", "DejaVu Sans", Arial, sans-serif;
      color: #17202a;
      font-size: 10.5pt;
      line-height: 1.42;
      text-align: justify;
    }}
    h1, h2, h3, h4 {{
      color: #0f172a;
      line-height: 1.2;
      page-break-after: avoid;
      text-align: left;
    }}
    h1 {{ font-size: 21pt; margin: 0 0 14px; }}
    h2 {{
      font-size: 15pt;
      margin: 0 0 8px;
      border-bottom: 1px solid #d8dee9;
      padding-bottom: 4px;
      page-break-before: always;
    }}
    h3 {{ font-size: 12.5pt; margin: 16px 0 6px; }}
    h4 {{ font-size: 11pt; margin: 12px 0 4px; }}
    p {{ margin: 7px 0; }}
    .toc h2 {{ page-break-before: auto; }}
    .toc-list {{
      margin-top: 12px;
      padding-left: 0;
      list-style: none;
      text-align: left;
    }}
    .toc-list li {{ margin: 5px 0; }}
    .toc-list .toc-h3 {{ margin-left: 18px; font-size: 9.8pt; }}
    .toc-list a {{ color: #0f3b75; text-decoration: none; }}
    .page-break {{ page-break-before: always; }}
    table {{
      width: 100%;
      border-collapse: collapse;
      margin: 9px 0 14px;
      page-break-inside: avoid;
      font-size: 8.8pt;
      text-align: left;
    }}
    th, td {{
      border: 1px solid #cbd5e1;
      padding: 5px 6px;
      vertical-align: top;
    }}
    th {{
      background: #e9eef5;
      font-weight: 700;
    }}
    pre {{
      background: #f6f8fb;
      border: 1px solid #d8dee9;
      border-radius: 5px;
      padding: 9px;
      overflow: hidden;
      white-space: pre-wrap;
      page-break-inside: avoid;
      font-size: 8.7pt;
      line-height: 1.25;
      text-align: left;
    }}
    code {{
      font-family: "Noto Sans Mono", "DejaVu Sans Mono", monospace;
      font-size: 0.92em;
      background: #f1f5f9;
      padding: 1px 3px;
      border-radius: 3px;
    }}
    pre code {{ background: transparent; padding: 0; border-radius: 0; }}
    .figure-caption {{
      margin-top: -7px;
      margin-bottom: 12px;
      font-size: 9pt;
      font-style: italic;
      color: #475569;
      text-align: center;
      page-break-before: avoid;
    }}
    ul {{ margin-top: 5px; margin-bottom: 10px; padding-left: 18px; }}
    li {{ margin: 3px 0; }}
  </style>
</head>
<body>
{body}
</body>
</html>
"""


def main() -> int:
    if not MARKDOWN.exists():
        print(f"Missing {MARKDOWN}", file=sys.stderr)
        return 1

    markdown_text = MARKDOWN.read_text(encoding="utf-8")
    toc, heading_ids = collect_toc(markdown_text)
    body = render_markdown(markdown_text, heading_ids)
    body = body.replace("</h1>", "</h1>\n" + render_toc(toc), 1)
    HTML.write_text(build_html(body), encoding="utf-8")
    subprocess.run(
        [
            "google-chrome",
            "--headless",
            "--disable-gpu",
            "--no-sandbox",
            "--no-pdf-header-footer",
            f"--print-to-pdf={PDF}",
            str(HTML),
        ],
        check=True,
    )
    print(PDF)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
