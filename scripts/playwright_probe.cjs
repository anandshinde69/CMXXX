#!/usr/bin/env node

const { chromium } = require("playwright");

function usage() {
  console.error(
    "Usage: node scripts/playwright_probe.cjs <url> [cardSelector]\n" +
      "Example:\n" +
      "  node scripts/playwright_probe.cjs 'https://allpornstream.com/?studio=ElegantAngel' 'div[data-thumb-id]'"
  );
}

function matchesInteresting(url) {
  return /(m3u8|mp4|mkv|api|graphql|playlist|stream|video|embed|download|_rsc=)/i.test(url);
}

function normalizeHref(href, pageUrl) {
  if (!href) return null;
  if (href.startsWith("http")) return href;
  if (href.startsWith("//")) return `https:${href}`;
  try {
    return new URL(href, pageUrl).toString();
  } catch {
    return null;
  }
}

async function inspectPage(page, targetUrl, cardSelector) {
  const networkHits = [];
  page.on("response", (resp) => {
    const url = resp.url();
    if (matchesInteresting(url)) {
      networkHits.push({
        url,
        status: resp.status(),
        contentType: resp.headers()["content-type"] || "",
      });
    }
  });

  await page.goto(targetUrl, { waitUntil: "domcontentloaded", timeout: 45000 });
  await page.waitForTimeout(8000);

  const pageInfo = await page.evaluate((selector) => {
    const cards = selector ? [...document.querySelectorAll(selector)] : [];
    const scripts = [...document.querySelectorAll("script")].map((s) => s.textContent || "");
    const allHtml = document.documentElement.outerHTML;
    const scriptHits = scripts
      .filter((text) => /streamtape|mixdrp|playmogo|vidguard|bigwarp|dood|filemoon|mp4|m3u8/i.test(text))
      .slice(0, 5);

    return {
      title: document.title,
      url: location.href,
      cardCount: cards.length,
      firstCards: cards.slice(0, 3).map((el) => ({
        text: (el.textContent || "").trim().slice(0, 120),
        href:
          el.getAttribute("href") ||
          el.getAttribute("data-href") ||
          el.querySelector("a[href]")?.getAttribute("href") ||
          null,
        title: el.getAttribute("title") || el.getAttribute("data-title") || null,
        img:
          el.querySelector("img")?.getAttribute("src") ||
          el.querySelector("img")?.getAttribute("data-src") ||
          null,
      })),
      iframes: [...document.querySelectorAll("iframe")]
        .map((el) => el.getAttribute("src") || el.getAttribute("data-src") || el.getAttribute("data-litespeed-src"))
        .filter(Boolean)
        .slice(0, 20),
      videoSources: [...document.querySelectorAll("video source, video, source")]
        .map((el) => ({
          tag: el.tagName,
          src: el.getAttribute("src"),
          label: el.getAttribute("label"),
        }))
        .filter((entry) => entry.src)
        .slice(0, 20),
      interestingLinks: [...document.querySelectorAll("a[href], [data-href], [data-url], button[title]")]
        .map((el) => ({
          href: el.getAttribute("href") || el.getAttribute("data-href") || el.getAttribute("data-url"),
          title: el.getAttribute("title"),
          text: (el.textContent || "").trim(),
        }))
        .filter(
          (entry) =>
            /(streamtape|mixdrp|playmogo|vidguard|bigwarp|dood|filemoon|download|watch|embed|player|m3u8|mp4)/i.test(
              `${entry.href || ""} ${entry.title || ""} ${entry.text}`
            )
        )
        .slice(0, 50),
      scriptHits,
      nextDataSnippets: ["streamtape", "mixdrp", "playmogo", "vidguard", "bigwarp", "dood", "filemoon"]
        .map((key) => {
          const idx = allHtml.indexOf(key);
          return idx >= 0 ? { key, snippet: allHtml.slice(Math.max(0, idx - 300), idx + 900) } : null;
        })
        .filter(Boolean),
    };
  }, cardSelector || "");

  return {
    pageInfo,
    networkHits: networkHits.slice(0, 150),
  };
}

async function main() {
  const targetUrl = process.argv[2];
  const cardSelector = process.argv[3] || "";

  if (!targetUrl) {
    usage();
    process.exit(1);
  }

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({
    userAgent: "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
  });

  try {
    const home = await inspectPage(page, targetUrl, cardSelector);
    console.log("=== Page ===");
    console.log(JSON.stringify(home, null, 2));

    const firstHref = home.pageInfo.firstCards[0]?.href;
    const detailUrl = normalizeHref(firstHref, home.pageInfo.url);

    if (detailUrl) {
      const detailPage = await browser.newPage({
        userAgent: "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
      });
      const detail = await inspectPage(detailPage, detailUrl, "");
      console.log("=== Detail ===");
      console.log(JSON.stringify(detail, null, 2));
      await detailPage.close();
    }
  } finally {
    await page.close();
    await browser.close();
  }
}

main().catch((err) => {
  console.error(String(err));
  process.exit(1);
});
