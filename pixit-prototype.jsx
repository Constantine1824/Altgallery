import { useState, useEffect, useRef } from "react";

const MOCK_FOLDERS = [
  { id: "memes", name: "Memes", icon: "😂", count: 47, color: "#FF6B35" },
  { id: "screenshots", name: "Screenshots", icon: "📱", count: 32, color: "#4ECDC4" },
  { id: "selfies", name: "Selfies", icon: "🤳", count: 21, color: "#A855F7" },
  { id: "nature", name: "Nature", icon: "🌿", count: 18, color: "#22C55E" },
  { id: "food", name: "Food", icon: "🍜", count: 14, color: "#EAB308" },
  { id: "receipts", name: "Receipts & Docs", icon: "🧾", count: 9, color: "#64748B" },
];

const MOCK_IMAGES = {
  memes: [
    { id: "m1", name: "IMG_2041.jpg", desc: "Drake meme format comparing two programming approaches, top panel shows person rejecting 'writing documentation', bottom panel approves 'adding TODO comments'", tags: ["drake", "programming", "documentation"], isMeme: true },
    { id: "m2", name: "IMG_2103.jpg", desc: "Distracted boyfriend meme, boyfriend labeled 'developers' looking at woman labeled 'new JavaScript framework' while girlfriend labeled 'current project' looks annoyed", tags: ["distracted boyfriend", "javascript", "developers"], isMeme: true },
    { id: "m3", name: "IMG_2250.jpg", desc: "Two Spider-Man pointing at each other meme, both labeled 'my code' and 'the bug'", tags: ["spiderman", "pointing", "bug", "code"], isMeme: true },
    { id: "m4", name: "IMG_2301.jpg", desc: "Expanding brain meme with four panels: 'print debugging', 'using a debugger', 'reading the error message', 'asking ChatGPT'", tags: ["expanding brain", "debugging", "chatgpt"], isMeme: true },
    { id: "m5", name: "IMG_2455.jpg", desc: "This is fine dog in burning room meme, dog labeled 'me during production deploy on Friday'", tags: ["this is fine", "dog", "fire", "production", "friday deploy"], isMeme: true },
    { id: "m6", name: "IMG_2512.jpg", desc: "Woman yelling at cat meme, woman says 'you said it would take 2 hours', cat labeled 'the 2 hour estimate was for the happy path'", tags: ["woman yelling cat", "estimate", "happy path"], isMeme: true },
  ],
  screenshots: [
    { id: "s1", name: "Screenshot_20240315.png", desc: "WhatsApp conversation about weekend plans, mentions Lekki beach trip Saturday", tags: ["whatsapp", "chat", "lekki", "beach"], isMeme: false },
    { id: "s2", name: "Screenshot_20240401.png", desc: "Twitter thread about Nigerian tech ecosystem growth, statistics about startup funding", tags: ["twitter", "nigeria", "tech", "startup"], isMeme: false },
    { id: "s3", name: "Screenshot_20240412.png", desc: "Code snippet showing Python FastAPI endpoint with Pydantic model validation", tags: ["code", "python", "fastapi", "pydantic"], isMeme: false },
  ],
  selfies: [
    { id: "se1", name: "IMG_1980.jpg", desc: "Person smiling at camera, indoor setting with warm lighting, wearing black hoodie", tags: ["selfie", "indoor", "hoodie"], isMeme: false },
    { id: "se2", name: "IMG_2100.jpg", desc: "Group selfie with four people at tech meetup, AI Saturday Lagos banner visible in background", tags: ["group", "meetup", "ai saturday", "lagos"], isMeme: false },
  ],
  nature: [
    { id: "n1", name: "IMG_2200.jpg", desc: "Sunset over Lagos lagoon, orange and purple sky reflected in water, silhouette of buildings", tags: ["sunset", "lagos", "lagoon", "water"], isMeme: false },
    { id: "n2", name: "IMG_2350.jpg", desc: "Close-up of green tropical plant with water droplets on leaves after rain", tags: ["plant", "tropical", "rain", "green"], isMeme: false },
  ],
  food: [
    { id: "f1", name: "IMG_2180.jpg", desc: "Plate of jollof rice with fried plantain and grilled chicken, served on white plate", tags: ["jollof", "rice", "plantain", "chicken"], isMeme: false },
    { id: "f2", name: "IMG_2290.jpg", desc: "Suya skewers on newspaper with sliced onions and tomatoes, street food stall lighting", tags: ["suya", "street food", "skewers"], isMeme: false },
  ],
  receipts: [
    { id: "r1", name: "IMG_2400.jpg", desc: "POS receipt from Shoprite showing grocery items, total amount 15,400 naira, dated March 2024", tags: ["receipt", "shoprite", "groceries", "pos"], isMeme: false },
  ],
};

const SEARCH_INDEX = Object.values(MOCK_IMAGES).flat();

// Simple semantic similarity (keyword + fuzzy)
function searchImages(query) {
  const q = query.toLowerCase().trim();
  if (!q) return [];
  const terms = q.split(/\s+/);
  return SEARCH_INDEX.map((img) => {
    let score = 0;
    const searchable = `${img.desc} ${img.tags.join(" ")} ${img.name}`.toLowerCase();
    for (const t of terms) {
      if (searchable.includes(t)) score += 3;
      for (const tag of img.tags) {
        if (tag.includes(t) || t.includes(tag)) score += 2;
      }
    }
    // Boost meme-related queries
    if (
      (q.includes("meme") || q.includes("funny")) &&
      img.isMeme
    )
      score += 5;
    return { ...img, score };
  })
    .filter((r) => r.score > 0)
    .sort((a, b) => b.score - a.score);
}

// Generate placeholder image with color
function PlaceholderImg({ img, size = 120 }) {
  const hue = (img.id.charCodeAt(0) * 47 + img.id.charCodeAt(1) * 131) % 360;
  const isMeme = img.isMeme;
  return (
    <div
      style={{
        width: size,
        height: size,
        borderRadius: 10,
        background: isMeme
          ? `linear-gradient(135deg, hsl(${hue}, 70%, 25%), hsl(${(hue + 40) % 360}, 80%, 35%))`
          : `linear-gradient(135deg, hsl(${hue}, 40%, 75%), hsl(${(hue + 30) % 360}, 50%, 85%))`,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        position: "relative",
        overflow: "hidden",
      }}
    >
      {isMeme && (
        <span style={{ fontSize: size * 0.35, filter: "grayscale(0.2)" }}>😂</span>
      )}
      {!isMeme && (
        <span style={{ fontSize: size * 0.3 }}>🖼️</span>
      )}
      <span
        style={{
          fontSize: 8,
          color: isMeme ? "rgba(255,255,255,0.6)" : "rgba(0,0,0,0.4)",
          marginTop: 4,
          fontFamily: "'JetBrains Mono', monospace",
        }}
      >
        {img.name.slice(0, 12)}
      </span>
    </div>
  );
}

// Processing animation
function ProcessingOverlay({ progress, batch, total }) {
  return (
    <div
      style={{
        position: "fixed",
        inset: 0,
        background: "rgba(10, 10, 14, 0.95)",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        zIndex: 100,
        fontFamily: "'Space Mono', monospace",
      }}
    >
      <div style={{ position: "relative", width: 120, height: 120, marginBottom: 32 }}>
        <svg width="120" height="120" viewBox="0 0 120 120">
          <circle cx="60" cy="60" r="52" fill="none" stroke="#1a1a2e" strokeWidth="6" />
          <circle
            cx="60" cy="60" r="52"
            fill="none"
            stroke="#FF6B35"
            strokeWidth="6"
            strokeLinecap="round"
            strokeDasharray={`${progress * 3.27} 327`}
            transform="rotate(-90 60 60)"
            style={{ transition: "stroke-dasharray 0.3s ease" }}
          />
        </svg>
        <div
          style={{
            position: "absolute",
            inset: 0,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            color: "#FF6B35",
            fontSize: 22,
            fontWeight: 700,
          }}
        >
          {Math.round(progress)}%
        </div>
      </div>
      <p style={{ color: "#ccc", fontSize: 14, marginBottom: 8, letterSpacing: 1 }}>
        PROCESSING BATCH {batch}/{total}
      </p>
      <p style={{ color: "#666", fontSize: 11 }}>
        Classifying · Generating descriptions · Building index
      </p>
      <div
        style={{
          width: 240,
          height: 3,
          background: "#1a1a2e",
          borderRadius: 2,
          marginTop: 24,
          overflow: "hidden",
        }}
      >
        <div
          style={{
            width: `${progress}%`,
            height: "100%",
            background: "linear-gradient(90deg, #FF6B35, #FF8F65)",
            borderRadius: 2,
            transition: "width 0.3s ease",
          }}
        />
      </div>
    </div>
  );
}

export default function PixIt() {
  const [view, setView] = useState("home"); // home | folder | detail | search | processing
  const [selectedFolder, setSelectedFolder] = useState(null);
  const [selectedImage, setSelectedImage] = useState(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState([]);
  const [processProgress, setProcessProgress] = useState(0);
  const [processBatch, setProcessBatch] = useState(1);
  const searchRef = useRef(null);

  // Simulate processing
  function startProcessing() {
    setView("processing");
    setProcessProgress(0);
    setProcessBatch(1);
    let p = 0;
    const interval = setInterval(() => {
      p += Math.random() * 4 + 1;
      if (p >= 100) {
        p = 100;
        clearInterval(interval);
        setTimeout(() => setView("home"), 600);
      }
      setProcessProgress(p);
      setProcessBatch(p < 50 ? 1 : 2);
    }, 120);
  }

  function handleSearch(q) {
    setSearchQuery(q);
    if (q.length > 1) {
      setSearchResults(searchImages(q));
    } else {
      setSearchResults([]);
    }
  }

  function openFolder(folder) {
    setSelectedFolder(folder);
    setView("folder");
  }

  function openImage(img) {
    setSelectedImage(img);
    setView("detail");
  }

  function goBack() {
    if (view === "detail" && searchQuery) {
      setView("search");
    } else if (view === "detail") {
      setView("folder");
    } else {
      setView("home");
      setSearchQuery("");
      setSearchResults([]);
    }
  }

  if (view === "processing") {
    return (
      <ProcessingOverlay progress={processProgress} batch={processBatch} total={2} />
    );
  }

  return (
    <div
      style={{
        minHeight: "100vh",
        background: "#0a0a0e",
        color: "#e8e6e3",
        fontFamily: "'Outfit', 'Segoe UI', sans-serif",
        maxWidth: 430,
        margin: "0 auto",
        position: "relative",
        overflow: "hidden",
      }}
    >
      <link
        href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700;800&family=Space+Mono:wght@400;700&family=JetBrains+Mono:wght@400;600&display=swap"
        rel="stylesheet"
      />

      {/* Header */}
      <div
        style={{
          padding: "20px 20px 0",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
        }}
      >
        {view !== "home" ? (
          <button
            onClick={goBack}
            style={{
              background: "none",
              border: "none",
              color: "#FF6B35",
              fontSize: 24,
              cursor: "pointer",
              padding: 4,
            }}
          >
            ←
          </button>
        ) : (
          <div />
        )}
        <h1
          style={{
            fontFamily: "'Space Mono', monospace",
            fontSize: view === "home" ? 22 : 16,
            fontWeight: 700,
            letterSpacing: 3,
            color: "#FF6B35",
            margin: 0,
          }}
        >
          PIX:IT
        </h1>
        {view === "home" ? (
          <button
            onClick={() => {
              setView("search");
              setTimeout(() => searchRef.current?.focus(), 100);
            }}
            style={{
              background: "none",
              border: "none",
              color: "#888",
              fontSize: 22,
              cursor: "pointer",
            }}
          >
            🔍
          </button>
        ) : (
          <div style={{ width: 28 }} />
        )}
      </div>

      {/* Home View */}
      {view === "home" && (
        <div style={{ padding: 20 }}>
          {/* Stats bar */}
          <div
            style={{
              display: "flex",
              gap: 12,
              marginBottom: 28,
              padding: "14px 16px",
              background: "#13131a",
              borderRadius: 14,
              border: "1px solid #1e1e2e",
            }}
          >
            <div style={{ flex: 1, textAlign: "center" }}>
              <div style={{ fontSize: 20, fontWeight: 700, color: "#FF6B35" }}>141</div>
              <div style={{ fontSize: 10, color: "#666", letterSpacing: 1, fontFamily: "'Space Mono', monospace" }}>INDEXED</div>
            </div>
            <div style={{ width: 1, background: "#1e1e2e" }} />
            <div style={{ flex: 1, textAlign: "center" }}>
              <div style={{ fontSize: 20, fontWeight: 700, color: "#4ECDC4" }}>6</div>
              <div style={{ fontSize: 10, color: "#666", letterSpacing: 1, fontFamily: "'Space Mono', monospace" }}>FOLDERS</div>
            </div>
            <div style={{ width: 1, background: "#1e1e2e" }} />
            <div style={{ flex: 1, textAlign: "center" }}>
              <div style={{ fontSize: 20, fontWeight: 700, color: "#A855F7" }}>47</div>
              <div style={{ fontSize: 10, color: "#666", letterSpacing: 1, fontFamily: "'Space Mono', monospace" }}>MEMES</div>
            </div>
          </div>

          {/* Scan button */}
          <button
            onClick={startProcessing}
            style={{
              width: "100%",
              padding: "14px",
              background: "linear-gradient(135deg, #FF6B35, #FF8F65)",
              border: "none",
              borderRadius: 12,
              color: "#fff",
              fontSize: 14,
              fontWeight: 600,
              fontFamily: "'Space Mono', monospace",
              letterSpacing: 2,
              cursor: "pointer",
              marginBottom: 28,
              boxShadow: "0 4px 24px rgba(255,107,53,0.25)",
            }}
          >
            ▶ SCAN NEW PHOTOS
          </button>

          {/* Folder grid */}
          <div
            style={{
              display: "grid",
              gridTemplateColumns: "1fr 1fr",
              gap: 14,
            }}
          >
            {MOCK_FOLDERS.map((folder) => (
              <button
                key={folder.id}
                onClick={() => openFolder(folder)}
                style={{
                  background: "#13131a",
                  border: "1px solid #1e1e2e",
                  borderRadius: 16,
                  padding: "20px 16px",
                  cursor: "pointer",
                  textAlign: "left",
                  transition: "all 0.2s",
                  position: "relative",
                  overflow: "hidden",
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.borderColor = folder.color;
                  e.currentTarget.style.transform = "translateY(-2px)";
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.borderColor = "#1e1e2e";
                  e.currentTarget.style.transform = "translateY(0)";
                }}
              >
                <div
                  style={{
                    position: "absolute",
                    top: 0,
                    right: 0,
                    width: 60,
                    height: 60,
                    background: `radial-gradient(circle at top right, ${folder.color}15, transparent)`,
                  }}
                />
                <span style={{ fontSize: 28 }}>{folder.icon}</span>
                <div
                  style={{
                    color: "#e8e6e3",
                    fontSize: 14,
                    fontWeight: 600,
                    marginTop: 10,
                  }}
                >
                  {folder.name}
                </div>
                <div
                  style={{
                    color: "#555",
                    fontSize: 11,
                    fontFamily: "'Space Mono', monospace",
                    marginTop: 4,
                  }}
                >
                  {folder.count} items
                </div>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Folder View */}
      {view === "folder" && selectedFolder && (
        <div style={{ padding: 20 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 20 }}>
            <span style={{ fontSize: 28 }}>{selectedFolder.icon}</span>
            <div>
              <h2 style={{ margin: 0, fontSize: 18, fontWeight: 600 }}>
                {selectedFolder.name}
              </h2>
              <span style={{ color: "#666", fontSize: 12, fontFamily: "'Space Mono', monospace" }}>
                {selectedFolder.count} items
              </span>
            </div>
          </div>
          <div
            style={{
              display: "grid",
              gridTemplateColumns: "repeat(3, 1fr)",
              gap: 8,
            }}
          >
            {(MOCK_IMAGES[selectedFolder.id] || []).map((img) => (
              <button
                key={img.id}
                onClick={() => openImage(img)}
                style={{
                  background: "none",
                  border: "none",
                  cursor: "pointer",
                  padding: 0,
                }}
              >
                <PlaceholderImg img={img} size={"100%"} />
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Detail View */}
      {view === "detail" && selectedImage && (
        <div style={{ padding: 20 }}>
          <div style={{ display: "flex", justifyContent: "center", marginBottom: 20 }}>
            <PlaceholderImg img={selectedImage} size={280} />
          </div>
          <div
            style={{
              background: "#13131a",
              borderRadius: 14,
              padding: 18,
              border: "1px solid #1e1e2e",
            }}
          >
            <div
              style={{
                fontFamily: "'JetBrains Mono', monospace",
                fontSize: 11,
                color: "#666",
                marginBottom: 12,
              }}
            >
              {selectedImage.name}
            </div>
            {selectedImage.isMeme && (
              <span
                style={{
                  display: "inline-block",
                  background: "#FF6B3520",
                  color: "#FF6B35",
                  fontSize: 10,
                  fontWeight: 600,
                  padding: "3px 10px",
                  borderRadius: 20,
                  marginBottom: 12,
                  fontFamily: "'Space Mono', monospace",
                  letterSpacing: 1,
                }}
              >
                MEME
              </span>
            )}
            <h3
              style={{
                margin: "0 0 8px",
                fontSize: 13,
                fontWeight: 600,
                color: "#999",
                fontFamily: "'Space Mono', monospace",
                letterSpacing: 0.5,
              }}
            >
              AI DESCRIPTION
            </h3>
            <p style={{ margin: 0, fontSize: 14, lineHeight: 1.6, color: "#ccc" }}>
              {selectedImage.desc}
            </p>
            <div style={{ marginTop: 16, display: "flex", flexWrap: "wrap", gap: 6 }}>
              {selectedImage.tags.map((tag) => (
                <span
                  key={tag}
                  style={{
                    background: "#1e1e2e",
                    color: "#888",
                    fontSize: 11,
                    padding: "4px 10px",
                    borderRadius: 20,
                    fontFamily: "'JetBrains Mono', monospace",
                  }}
                >
                  #{tag}
                </span>
              ))}
            </div>
          </div>

          {/* Metadata JSON preview */}
          <details style={{ marginTop: 16 }}>
            <summary
              style={{
                color: "#555",
                fontSize: 11,
                fontFamily: "'Space Mono', monospace",
                cursor: "pointer",
                letterSpacing: 1,
              }}
            >
              VIEW JSON METADATA
            </summary>
            <pre
              style={{
                background: "#13131a",
                border: "1px solid #1e1e2e",
                borderRadius: 10,
                padding: 14,
                marginTop: 8,
                fontSize: 10,
                color: "#4ECDC4",
                fontFamily: "'JetBrains Mono', monospace",
                overflow: "auto",
                lineHeight: 1.6,
              }}
            >
              {JSON.stringify(
                {
                  file_name: selectedImage.name,
                  content_uri: `content://media/external/images/media/${selectedImage.id}`,
                  is_meme: selectedImage.isMeme,
                  description: selectedImage.desc,
                  tags: selectedImage.tags,
                  cluster: selectedFolder?.id || "uncategorized",
                  processed_at: "2024-04-15T14:32:00Z",
                  model_version: "pixit-v0.1",
                },
                null,
                2
              )}
            </pre>
          </details>
        </div>
      )}

      {/* Search View */}
      {view === "search" && (
        <div style={{ padding: 20 }}>
          <div
            style={{
              background: "#13131a",
              border: "1px solid #1e1e2e",
              borderRadius: 12,
              padding: "10px 16px",
              display: "flex",
              alignItems: "center",
              gap: 10,
              marginBottom: 20,
            }}
          >
            <span style={{ color: "#555", fontSize: 18 }}>🔍</span>
            <input
              ref={searchRef}
              type="text"
              value={searchQuery}
              onChange={(e) => handleSearch(e.target.value)}
              placeholder="Search by memory... e.g. 'drake programming'"
              style={{
                background: "none",
                border: "none",
                color: "#e8e6e3",
                fontSize: 14,
                fontFamily: "'Outfit', sans-serif",
                outline: "none",
                width: "100%",
              }}
            />
          </div>

          {/* Search suggestions */}
          {searchQuery.length <= 1 && (
            <div>
              <p style={{ color: "#555", fontSize: 12, fontFamily: "'Space Mono', monospace", letterSpacing: 1, marginBottom: 12 }}>
                TRY SEARCHING
              </p>
              {["drake meme", "jollof rice", "friday deploy", "lagos sunset", "receipt shoprite", "spiderman bug"].map((s) => (
                <button
                  key={s}
                  onClick={() => {
                    handleSearch(s);
                    setSearchQuery(s);
                  }}
                  style={{
                    display: "inline-block",
                    background: "#1a1a24",
                    border: "1px solid #252535",
                    color: "#999",
                    fontSize: 12,
                    padding: "7px 14px",
                    borderRadius: 20,
                    margin: "0 6px 8px 0",
                    cursor: "pointer",
                    fontFamily: "'JetBrains Mono', monospace",
                  }}
                >
                  {s}
              </button>
              ))}
            </div>
          )}

          {/* Results */}
          {searchResults.length > 0 && (
            <div>
              <p style={{ color: "#555", fontSize: 11, fontFamily: "'Space Mono', monospace", letterSpacing: 1, marginBottom: 12 }}>
                {searchResults.length} RESULT{searchResults.length !== 1 ? "S" : ""}
              </p>
              {searchResults.map((img) => (
                <button
                  key={img.id}
                  onClick={() => openImage(img)}
                  style={{
                    width: "100%",
                    display: "flex",
                    gap: 14,
                    alignItems: "center",
                    background: "#13131a",
                    border: "1px solid #1e1e2e",
                    borderRadius: 12,
                    padding: 12,
                    marginBottom: 8,
                    cursor: "pointer",
                    textAlign: "left",
                  }}
                >
                  <PlaceholderImg img={img} size={56} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ color: "#ccc", fontSize: 13, fontWeight: 500, marginBottom: 4 }}>
                      {img.name}
                    </div>
                    <div
                      style={{
                        color: "#777",
                        fontSize: 11,
                        lineHeight: 1.4,
                        overflow: "hidden",
                        textOverflow: "ellipsis",
                        display: "-webkit-box",
                        WebkitLineClamp: 2,
                        WebkitBoxOrient: "vertical",
                      }}
                    >
                      {img.desc}
                    </div>
                  </div>
                  <div
                    style={{
                      background: `${img.isMeme ? "#FF6B35" : "#4ECDC4"}20`,
                      color: img.isMeme ? "#FF6B35" : "#4ECDC4",
                      fontSize: 9,
                      fontWeight: 600,
                      padding: "2px 8px",
                      borderRadius: 10,
                      fontFamily: "'Space Mono', monospace",
                      flexShrink: 0,
                    }}
                  >
                    {img.isMeme ? "MEME" : "PHOTO"}
                  </div>
                </button>
              ))}
            </div>
          )}

          {searchQuery.length > 1 && searchResults.length === 0 && (
            <div style={{ textAlign: "center", padding: "40px 0" }}>
              <span style={{ fontSize: 40 }}>🔍</span>
              <p style={{ color: "#555", fontSize: 13, marginTop: 12 }}>
                No matches for "{searchQuery}"
              </p>
              <p style={{ color: "#444", fontSize: 11 }}>
                Try different keywords from what you remember
              </p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
