#!/bin/bash

# Download Excalidraw and dependencies for offline use

ASSETS_DIR="app/src/main/assets"
LIB_DIR="$ASSETS_DIR/lib"
EXCALIDRAW_VERSION="0.17.6"
REACT_VERSION="18.2.0"

mkdir -p "$LIB_DIR"
mkdir -p "$LIB_DIR/excalidraw-assets"

BASE_UNPKG="https://unpkg.com"
BASE_CDN="https://cdn.jsdelivr.net/npm"
EXCALIDRAW_DIST="@excalidraw/excalidraw@${EXCALIDRAW_VERSION}/dist"

echo "Downloading React and Excalidraw libraries..."
echo ""

echo "[1/3] Downloading React ${REACT_VERSION}..."
curl -fL -o "$LIB_DIR/react.production.min.js" \
  "${BASE_UNPKG}/react@${REACT_VERSION}/umd/react.production.min.js"

echo "[2/3] Downloading React DOM ${REACT_VERSION}..."
curl -fL -o "$LIB_DIR/react-dom.production.min.js" \
  "${BASE_UNPKG}/react-dom@${REACT_VERSION}/umd/react-dom.production.min.js"

echo "[3/3] Downloading Excalidraw v${EXCALIDRAW_VERSION}..."
curl -fL -o "$LIB_DIR/excalidraw.production.min.js" \
  "${BASE_CDN}/${EXCALIDRAW_DIST}/excalidraw.production.min.js"

echo ""
echo "Downloading Excalidraw font assets..."
echo "(These must be local — the bundle loads them from EXCALIDRAW_ASSET_PATH)"

FONTS="Virgil.woff2 Cascadia.woff2 Assistant-Regular.woff2 Assistant-Medium.woff2 Assistant-SemiBold.woff2 Assistant-Bold.woff2"

for font in $FONTS; do
    echo "  Downloading $font..."
    curl -fL -o "$LIB_DIR/excalidraw-assets/$font" \
      "${BASE_CDN}/${EXCALIDRAW_DIST}/excalidraw-assets/$font"
    if [ $? -ne 0 ]; then
        echo "  WARNING: Failed to download $font"
    fi
done

echo ""
echo "Download complete!"
echo ""

# Show file sizes
echo "Library sizes:"
ls -lh "$LIB_DIR/"*.js 2>/dev/null
echo ""
echo "Font assets:"
ls -lh "$LIB_DIR/excalidraw-assets/" 2>/dev/null

echo ""

# Validate
EXCALIDRAW_SIZE=$(wc -c < "$LIB_DIR/excalidraw.production.min.js" 2>/dev/null || echo "0")
REACT_SIZE=$(wc -c < "$LIB_DIR/react.production.min.js" 2>/dev/null || echo "0")
REACTDOM_SIZE=$(wc -c < "$LIB_DIR/react-dom.production.min.js" 2>/dev/null || echo "0")

if [ "$EXCALIDRAW_SIZE" -gt 100000 ] && [ "$REACT_SIZE" -gt 5000 ] && [ "$REACTDOM_SIZE" -gt 50000 ]; then
    echo "✓ All libraries downloaded successfully"
    echo "✓ React: $(($REACT_SIZE / 1024))KB, React-DOM: $(($REACTDOM_SIZE / 1024))KB, Excalidraw: $(($EXCALIDRAW_SIZE / 1024))KB"
else
    echo "⚠ Warning: Some downloads may have failed"
    echo "  React: ${REACT_SIZE} bytes (need >5KB)"
    echo "  React-DOM: ${REACTDOM_SIZE} bytes (need >50KB)"
    echo "  Excalidraw: ${EXCALIDRAW_SIZE} bytes (need >100KB)"
fi

VIRGIL_SIZE=$(wc -c < "$LIB_DIR/excalidraw-assets/Virgil.woff2" 2>/dev/null || echo "0")
if [ "$VIRGIL_SIZE" -gt 10000 ]; then
    echo "✓ Font assets downloaded"
else
    echo "⚠ Warning: Font assets may be missing (Virgil.woff2: ${VIRGIL_SIZE} bytes)"
fi
echo ""
