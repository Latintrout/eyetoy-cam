// JPEG helpers: the Huffman-table fix (port of JpegFix.java) and decoding with
// Windows Imaging Component (WIC), which ships with every Windows version.
#pragma once
#include <cstdint>
#include <string>
#include <vector>

struct IWICImagingFactory;

namespace jpeg {

bool looksLikeJpeg(const std::vector<uint8_t> &j);

// Returns the frame with the standard Huffman tables (JPEG spec Annex K)
// inserted before the scan if it has none; otherwise returns it unchanged.
std::vector<uint8_t> addHuffmanTables(const std::vector<uint8_t> &j);

std::string hex(const std::vector<uint8_t> &d, size_t max);

// Decodes to top-down 32-bit BGRX pixels. The factory must belong to the
// calling thread (create one per thread after CoInitializeEx).
bool decode(IWICImagingFactory *f, const std::vector<uint8_t> &j,
            std::vector<uint8_t> &pixels, int &w, int &h);

} // namespace jpeg
