# Audited Implementation Plan: Native-Side FNV-1a Hash Calculation

This plan details the implementation of **Optimization 3: Native-Side FNV-1a Hash Calculation** to offload screen content hashing from the JVM UI thread to high-performance compiled Zig code.

---

## 1. Goal
Currently, the FNV-1a content hash is calculated on the JVM UI thread inside [ScreenSnapshot.java](file:///Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java#L725-L747) whenever a row is deserialized. This duplicates traversal effort. By moving it to Zig, we offload this loop to compiled, vectorized machine code and completely eliminate the content hashing loops on the JVM thread.

---

## 2. Binary Layout Changes
We insert the 64-bit hash at byte offset 8 (right after the line wrap flag), maintaining perfect 8-byte alignment.

```
Row Offset Map:
├── UTF-16 length: u32 (Offset 0, size 4)
├── Line wrap flag: u32 (Offset 4, size 4)
├── Content Hash: u64 (Offset 8, size 8)               <-- NEW
├── Cell Starts Array: columns * i32 (Offset 16, size cols*4)
├── Cell Lengths Array: columns * u16 (Offset 16 + cols*4, size cols*2)
├── Cell Display Widths Array: columns * u8 (Offset 16 + cols*6, size cols)
├── Inner Alignment Padding: 0-7 bytes
├── Cell Styles Array: columns * u64 (aligned to 8 bytes, size cols*8)
└── UTF-16 Characters Array: charsUsed * u16 (size charsUsed*2)
```

---

## 3. Native Implementation (`termux_ghostty.zig`)
In [termux_ghostty.zig](file:///Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/zig/src/termux_ghostty.zig):

1. **Implement FNV-1a mixing helper in `writeSnapshotRow`**:
```zig
const FNV_OFFSET_BASIS: u64 = 0xcbf29ce484222325;
const FNV_PRIME: u64 = 0x100000001b3;

inline fn mixHash(hash: u64, value: u64) u64 {
    return (hash ^ value) *% FNV_PRIME;
}
```

2. **Calculate hash on the scratch arrays prior to serialization**:
```zig
var hash: u64 = FNV_OFFSET_BASIS;
hash = mixHash(hash, @as(u64, self.scratch_utf16.items.len));
hash = mixHash(hash, @as(u64, self.render_state.cols));
hash = mixHash(hash, @as(u64, @intFromBool(row_rows[row_index].wrap)));
hash = mixHash(hash, 1); // mHasCellLayout is always 1 (true) for serialized rows

for (self.scratch_utf16.items) |char| {
    hash = mixHash(hash, @as(u64, char));
}
for (self.scratch_cell_styles.items) |style| {
    hash = mixHash(hash, style);
}
for (0..self.render_state.cols) |i| {
    // Safe sign-extension conversion from i32 to i64, then bitcast to u64
    hash = mixHash(hash, @bitCast(@as(i64, self.scratch_cell_starts.items[i])));
    hash = mixHash(hash, @as(u64, self.scratch_cell_lengths.items[i]));
    hash = mixHash(hash, @as(u64, self.scratch_cell_widths.items[i]));
}
```

3. **Update Sizing and Constants**:
Increase `snapshot_row_header_bytes` to 16 bytes:
```zig
const snapshot_row_header_bytes: usize = 2 * @sizeOf(u32) + @sizeOf(u64); // 16 bytes
```
Ensure `snapshotRowRequiredBytes` uses this updated header offset.

4. **Write the hash**:
Write the computed hash immediately after `lineWrap` inside `writeSnapshotRow`:
```zig
try writer.writeU64(hash);
```

---

## 4. JVM Java Implementation (`ScreenSnapshot.java`)
In [ScreenSnapshot.java](file:///Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java):

1. **Read the hash during row deserialization**:
Inside `parseNativeSnapshot()`:
```java
// After reading charsUsed and lineWrap:
long contentHash = buffer.getLong();
row.mContentHash = contentHash;
```
2. **Remove FNV-1a Hashing Overhead**:
Delete the `updateContentHash()` method and its calls inside `finishNative()`, `markMutated()`, etc., relying entirely on `mContentHash` being set from the native buffer.

---

## 5. Test Suite Adjustments (Critical)
Because the binary layout size is modified, tests using mock buffers must be adjusted:
1. **`RenderFrameCacheTest.java`**: Update `writeBlankRow` to write an extra `0L` for the hash:
   ```java
   buffer.putInt(0); // charsUsed
   buffer.putInt(0); // lineWrap
   buffer.putLong(0L); // contentHash (NEW)
   ```
2. **`ScreenSnapshotE2ETest.java`**: Update the test serializer to compute the FNV-1a hash matching the original algorithm and put it into the byte buffer.
