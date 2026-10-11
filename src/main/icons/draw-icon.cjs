// Draws the icon of the program, a gauge, as PNG files of four sizes into src/main/resources/icons.
//
//     node src/main/icons/draw-icon.cjs
//
// The files it writes are kept with the sources; this is run again only when the icon is to look different. Each size is
// drawn for itself, from the same shapes, with lines that are thicker in proportion where there are few pixels, and
// not scaled from another size. It needs nothing but Node: the PNG is written here, by hand.

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const SIZES = [16, 32, 48, 256];
const OUT = path.join(__dirname, '..', 'resources', 'icons');

// The colours: the dark of the dark theme's background, the green of the figures, and the lighter green of the dark theme.
const SQUARE = [0x0d, 0x11, 0x17];
const ARC = [0x1a, 0x7f, 0x37];
const NEEDLE = [0x3f, 0xb9, 0x50];

/** What the icon is at a point of the unit square: a colour, or null for nothing (outside the rounded square). */
function colourAt(x, y, shape) {
    // The rounded square that is the ground.
    const r = shape.corner;
    const cx = Math.min(Math.max(x, r), 1 - r);
    const cy = Math.min(Math.max(y, r), 1 - r);
    if (Math.hypot(x - cx, y - cy) > r) {
        return null;
    }
    // The needle and its hub, over the arc: from the centre of the gauge towards the upper right, at two thirds of the way round.
    const gx = 0.5;
    const gy = shape.centreY;
    const angle = Math.PI * (1 - 2 / 3);
    const tipX = gx + shape.needle * Math.cos(angle);
    const tipY = gy - shape.needle * Math.sin(angle);
    if (Math.hypot(x - gx, y - gy) <= shape.hub || distanceToSegment(x, y, gx, gy, tipX, tipY) <= shape.needleWidth / 2) {
        return NEEDLE;
    }
    // The arc: the upper half of a ring, with round ends.
    const d = Math.hypot(x - gx, y - gy);
    const onRing = Math.abs(d - shape.radius) <= shape.thickness / 2;
    const leftEnd = Math.hypot(x - (gx - shape.radius), y - gy) <= shape.thickness / 2;
    const rightEnd = Math.hypot(x - (gx + shape.radius), y - gy) <= shape.thickness / 2;
    if ((onRing && y <= gy) || leftEnd || rightEnd) {
        return ARC;
    }
    return SQUARE;
}

function distanceToSegment(x, y, ax, ay, bx, by) {
    const dx = bx - ax;
    const dy = by - ay;
    const t = Math.min(1, Math.max(0, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
    return Math.hypot(x - (ax + t * dx), y - (ay + t * dy));
}

/** The measures of the shapes for a size: the fewer the pixels, the thicker the lines in proportion, so that they are seen. */
function shapeFor(size) {
    const small = size <= 16;
    const middle = size <= 48;
    return {
        corner: 0.2,
        centreY: 0.66,
        radius: small ? 0.31 : 0.32,
        thickness: small ? 0.15 : middle ? 0.12 : 0.1,
        needle: small ? 0.3 : 0.31,
        needleWidth: small ? 0.13 : middle ? 0.09 : 0.07,
        hub: small ? 0.1 : middle ? 0.085 : 0.075,
    };
}

/** One size, as rows of RGBA: each pixel is the mean of a grid of samples in it, which is what makes its edges smooth. */
function draw(size) {
    const shape = shapeFor(size);
    const samples = size >= 256 ? 4 : 8;
    const pixels = Buffer.alloc(size * size * 4);
    for (let py = 0; py < size; py++) {
        for (let px = 0; px < size; px++) {
            let r = 0, g = 0, b = 0, covered = 0;
            for (let sy = 0; sy < samples; sy++) {
                for (let sx = 0; sx < samples; sx++) {
                    const colour = colourAt((px + (sx + 0.5) / samples) / size, (py + (sy + 0.5) / samples) / size, shape);
                    if (colour) {
                        r += colour[0];
                        g += colour[1];
                        b += colour[2];
                        covered++;
                    }
                }
            }
            const at = (py * size + px) * 4;
            if (covered > 0) {
                pixels[at] = Math.round(r / covered);
                pixels[at + 1] = Math.round(g / covered);
                pixels[at + 2] = Math.round(b / covered);
            }
            pixels[at + 3] = Math.round(255 * covered / (samples * samples));
        }
    }
    return pixels;
}

// ---- a PNG, written by hand: the signature, a header, the rows deflated, the end

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) {
        c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    return c >>> 0;
});

function crc(buffer) {
    let c = 0xffffffff;
    for (const byte of buffer) {
        c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
    }
    return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    const sum = Buffer.alloc(4);
    sum.writeUInt32BE(crc(body));
    return Buffer.concat([length, body, sum]);
}

function png(size, pixels) {
    const header = Buffer.alloc(13);
    header.writeUInt32BE(size, 0);
    header.writeUInt32BE(size, 4);
    header[8] = 8; // bits for each of red, green, blue and alpha
    header[9] = 6; // colour with alpha
    const rows = Buffer.alloc(size * (size * 4 + 1));
    for (let y = 0; y < size; y++) {
        // Each row begins with the filter it was written with: none.
        pixels.copy(rows, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
    }
    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        chunk('IHDR', header),
        chunk('IDAT', zlib.deflateSync(rows, { level: 9 })),
        chunk('IEND', Buffer.alloc(0)),
    ]);
}

fs.mkdirSync(OUT, { recursive: true });
for (const size of SIZES) {
    const file = path.join(OUT, 'icon-' + size + '.png');
    fs.writeFileSync(file, png(size, draw(size)));
    console.log('wrote ' + path.relative(process.cwd(), file));
}
