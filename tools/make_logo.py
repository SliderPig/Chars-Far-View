# The little picture in the Mods list: a black cat on a hill, looking at blocky mountains fading into a pink sky.
import math
import os
from PIL import Image

W = H = 64
SCALE = 4
img = Image.new('RGB', (W, H))
px = img.load()


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


TOP, LOW = (92, 44, 110), (255, 170, 214)
for y in range(H):
    c = mix(TOP, LOW, min(1, y / 44))
    for x in range(W):
        px[x, y] = c

# three rows of mountains, stepped like the far terrain itself, each nearer one darker
layers = [
    (30, 7, 0.21, 0.0, (232, 150, 200), 4),
    (37, 8, 0.13, 1.7, (176, 104, 170), 3),
    (44, 6, 0.27, 3.1, (112, 66, 128), 2),
]
for base, amp, freq, phase, colour, step in layers:
    for x in range(W):
        h = base - amp * (0.6 * math.sin((x // step) * step * freq + phase) + 0.4 * math.sin((x // step) * step * freq * 2.3 + phase * 2))
        for y in range(int(h), H):
            px[x, y] = colour

# the hill
for x in range(W):
    top = 52 + int(4 * math.sin(x * 0.09 + 0.5))
    for y in range(top, H):
        px[x, y] = (54, 28, 66)

# the cat, from behind
CAT = [
    "..#.....#..",
    "..##...##..",
    "..#######..",
    "..#######..",
    "..#######..",
    "...#####...",
    "..#######..",
    ".#########.",
    ".#########.",
    ".#########.",
    ".##########",
    "..#########",
    "..#####..##",
]
ox, oy = 26, 39
for j, row in enumerate(CAT):
    for i, ch in enumerate(row):
        if ch == '#':
            px[ox + i, oy + j] = (14, 8, 16)

out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'src', 'main', 'resources', 'charlod_logo.png')
img.resize((W * SCALE, H * SCALE), Image.NEAREST).save(out)
print(out)
