#!/usr/bin/env python3
"""리소스팩 그림·폰트·모델 생성기. 사용: python3 scripts/gen-art.py

모든 그림은 이 스크립트가 코드로 그린다(외부 이미지 없음). 결과는 src/main/resourcepack 아래에 쓰고 저장소에 커밋한다.
글리프 코드포인트·크기는 src/main/java/.../ui/Glyphs.java 와 맞아야 한다(ResourcePackTest가 검사).
"""
import json, os, struct, zlib, math

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resourcepack')
PACK_FORMAT = 75  # Minecraft 1.21.11

# ---------------- PNG ----------------
class Img:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[(0, 0, 0, 0)] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            if len(c) == 3: c = (*c, 255)
            self.px[y][x] = c

    def get(self, x, y):
        return self.px[y][x]

    def rect(self, x, y, w, h, c):
        for j in range(y, y + h):
            for i in range(x, x + w): self.set(i, j, c)

    def frame(self, x, y, w, h, c):
        for i in range(x, x + w): self.set(i, y, c); self.set(i, y + h - 1, c)
        for j in range(y, y + h): self.set(x, j, c); self.set(x + w - 1, j, c)

    def ellipse(self, cx, cy, rx, ry, c):
        for j in range(int(cy - ry) - 1, int(cy + ry) + 2):
            for i in range(int(cx - rx) - 1, int(cx + rx) + 2):
                if ((i + .5 - cx) / rx) ** 2 + ((j + .5 - cy) / ry) ** 2 <= 1: self.set(i, j, c)

    def poly(self, pts, c):
        ys = [p[1] for p in pts]
        for j in range(int(min(ys)), int(max(ys)) + 1):
            y = j + .5; xs = []
            for k in range(len(pts)):
                (x1, y1), (x2, y2) = pts[k], pts[(k + 1) % len(pts)]
                if (y1 <= y < y2) or (y2 <= y < y1):
                    xs.append(x1 + (y - y1) * (x2 - x1) / (y2 - y1))
            xs.sort()
            for a, b in zip(xs[::2], xs[1::2]):
                for i in range(int(round(a)), int(round(b))): self.set(i, j, c)

    def blit(self, src, x, y):
        for j in range(src.h):
            for i in range(src.w):
                c = src.px[j][i]
                if c[3]: self.set(x + i, y + j, c)

    def outline(self, c):
        """불투명 픽셀 둘레(투명 이웃)에 외곽선."""
        add = []
        for y in range(self.h):
            for x in range(self.w):
                if self.px[y][x][3]: continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < self.w and 0 <= ny < self.h and self.px[ny][nx][3] and self.px[ny][nx] != c:
                        add.append((x, y)); break
        for x, y in add: self.set(x, y, c)

    def save(self, rel):
        path = os.path.join(ROOT, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        raw = b''.join(b'\x00' + bytes(v for p in row for v in p) for row in self.px)
        def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
        data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', self.w, self.h, 8, 6, 0, 0, 0)) \
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
        with open(path, 'wb') as f: f.write(data)


def write_json(rel, obj):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=True, indent=2); f.write('\n')

# ---------------- 팔레트 ----------------
NAVY_0 = (10, 12, 24); NAVY_1 = (17, 21, 42); NAVY_2 = (24, 30, 58); NAVY_3 = (34, 42, 78)
CU_HI = (222, 178, 132); CU = (176, 124, 84); CU_MID = (128, 86, 58); CU_DK = (78, 50, 36); INK = (28, 20, 18)
RED = (196, 38, 44); RED_HI = (246, 92, 80); RED_DK = (110, 18, 26)
BLUE = (52, 96, 222); BLUE_HI = (110, 166, 255); BLUE_DK = (24, 40, 120)
GOLD = (236, 190, 70); GOLD_DK = (150, 100, 30)
WHITE = (240, 236, 228)

def copper_frame(img, x, y, w, h):
    img.frame(x, y, w, h, INK)
    img.frame(x + 1, y + 1, w - 2, h - 2, CU)
    for i in range(x + 1, x + w - 1): img.set(i, y + 1, CU_HI)
    for j in range(y + 1, y + h - 1): img.set(x + 1, j, CU_HI)
    img.frame(x + 2, y + 2, w - 4, h - 4, CU_DK)

def slot(img, x, y, size=18, bg=NAVY_0):
    img.rect(x, y, size, size, bg)
    for i in range(size): img.set(x + i, y, INK); img.set(x, y + i, INK)
    for i in range(size): img.set(x + i, y + size - 1, NAVY_3); img.set(x + size - 1, y + i, NAVY_3)

# 한글 라벨(직접 그린 픽셀 글자)
HANGUL = {
    '장': ["#####..#..", "..#....#..", ".#.#...###", "#...#..#..", ".......#..", "...####...", "..#....#..", "..#....#..", "...####..."],
    '비': ["#...#...#.", "#...#...#.", "#####...#.", "#...#...#.", "#####...#.", "........#.", "........#.", "........#.", "........#."],
    '스': ["....#....", "...#.#...", "..#...#..", ".#.....#.", "#.......#", ".........", ".........", "#########", "........."],
    '탯': ["####.#.#", "#....#.#", "####.###", "#....#.#", "####.#.#", "........", "...#....", "..#.#...", ".#...#.."],
    '보': [".#.....#.", ".#.....#.", ".#######.", ".#.....#.", ".#######.", "....#....", "....#....", "#########", "........."],
    '상': ["..#....#.", ".#.#...##", "#...#..#.", ".......#.", "...###...", "..#...#..", "..#...#..", "...###...", "........."],
}

def label(img, text, cx, y, color=CU_HI):
    widths = [len(HANGUL[ch][0]) for ch in text]
    total = sum(widths) + 2 * (len(text) - 1)
    x = cx - total // 2
    for ch, w in zip(text, widths):
        for j, row in enumerate(HANGUL[ch]):
            for i, c in enumerate(row):
                if c == '#':
                    img.set(x + i + 1, y + j + 1, INK)
                    img.set(x + i, y + j, color)
        x += w + 2

# ---------------- 아이콘 ----------------
def icon_helmet(s=48):
    im = Img(s, s); k = s / 48
    im.ellipse(24 * k, 24 * k, 15 * k, 16 * k, RED)
    im.rect(int(9 * k), int(24 * k), int(30 * k), int(14 * k), RED)
    im.ellipse(20 * k, 18 * k, 7 * k, 8 * k, RED_HI)
    im.rect(int(17 * k), int(22 * k), int(14 * k), int(4 * k), INK)          # 눈 틈
    im.rect(int(22 * k), int(22 * k), int(4 * k), int(16 * k), INK)          # 코 틈
    im.rect(int(9 * k), int(36 * k), int(30 * k), int(3 * k), RED_DK)
    for i in range(int(14 * k)): im.rect(int((18 + i * .4) * k), int((8 - i * .3) * k), max(1, int(3 * k)), max(1, int(3 * k)), GOLD)  # 장식 깃
    im.outline(INK)
    return im

def icon_crystal(s=48):
    im = Img(s, s); k = s / 48
    im.poly([(24 * k, 4 * k), (40 * k, 22 * k), (24 * k, 44 * k), (8 * k, 22 * k)], BLUE)
    im.poly([(24 * k, 4 * k), (24 * k, 44 * k), (8 * k, 22 * k)], BLUE_HI)
    im.poly([(24 * k, 12 * k), (32 * k, 22 * k), (24 * k, 34 * k), (16 * k, 22 * k)], (180, 220, 255))
    im.poly([(24 * k, 4 * k), (40 * k, 22 * k), (24 * k, 22 * k)], BLUE_DK)
    im.outline(INK)
    return im

def icon_chest(s=48):
    im = Img(s, s); k = s / 48
    im.rect(int(8 * k), int(20 * k), int(32 * k), int(20 * k), CU_MID)
    im.ellipse(24 * k, 20 * k, 16 * k, 9 * k, CU)
    im.rect(int(8 * k), int(20 * k), int(32 * k), int(3 * k), GOLD)
    im.rect(int(8 * k), int(36 * k), int(32 * k), int(3 * k), GOLD_DK)
    im.rect(int(21 * k), int(18 * k), int(6 * k), int(9 * k), GOLD)
    im.rect(int(23 * k), int(22 * k), int(2 * k), int(3 * k), INK)
    for x in (12, 34): im.rect(int(x * k), int(14 * k), max(1, int(2 * k)), int(26 * k), GOLD_DK)
    im.outline(INK)
    return im

def icon16(name):
    im = Img(16, 16)
    if name == 'stat_strength':   # 검
        for i in range(10): im.rect(3 + i, 12 - i, 2, 2, (210, 214, 224))
        im.rect(2, 11, 5, 2, CU); im.rect(3, 13, 2, 2, CU_DK)
    elif name == 'stat_agility':  # 깃털
        im.poly([(13, 2), (9, 11), (4, 14), (6, 9)], (190, 240, 210)); im.rect(4, 12, 2, 2, (90, 160, 120))
    elif name == 'stat_resistance':  # 방패
        im.poly([(3, 2), (13, 2), (13, 8), (8, 14), (3, 8)], (120, 140, 170)); im.rect(7, 3, 2, 9, CU_HI)
    elif name == 'stat_vitality':  # 하트
        im.ellipse(5.5, 6, 3, 3, RED); im.ellipse(10.5, 6, 3, 3, RED); im.poly([(2.5, 7), (13.5, 7), (8, 13.5)], RED); im.set(5, 5, RED_HI)
    elif name == 'stat_focus':  # 눈
        im.ellipse(8, 8, 6, 3.5, WHITE); im.ellipse(8, 8, 2.5, 2.5, BLUE); im.set(8, 8, INK)
    elif name == 'stat_luck':  # 클로버
        for cx, cy in ((8, 4.5), (4.5, 8), (11.5, 8), (8, 11)): im.ellipse(cx, cy, 2.6, 2.6, (80, 190, 90))
        im.rect(8, 11, 1, 4, (50, 120, 60))
    elif name == 'stat_spirit':  # 별빛
        im.poly([(8, 1), (10, 6), (15, 8), (10, 10), (8, 15), (6, 10), (1, 8), (6, 6)], (190, 120, 255)); im.ellipse(8, 8, 2, 2, WHITE)
    elif name == 'plus':
        im.rect(6, 2, 4, 12, (90, 200, 100)); im.rect(2, 6, 12, 4, (90, 200, 100))
    elif name == 'back':
        im.poly([(2, 8), (8, 2), (8, 6), (14, 6), (14, 10), (8, 10), (8, 14)], CU_HI)
    elif name == 'close':
        for i in range(12): im.rect(2 + i, 2 + i, 2, 2, CU_HI); im.rect(12 - i, 2 + i, 2, 2, CU_HI)
    elif name == 'summary':  # 책
        im.rect(3, 2, 10, 12, (120, 60, 50)); im.rect(4, 3, 8, 10, (230, 220, 190)); im.rect(5, 5, 6, 1, CU_DK); im.rect(5, 8, 6, 1, CU_DK)
    elif name == 'blank':
        return im
    im.outline(INK)
    return im

# ---------------- GUI 배경(176x222, 상자 6줄) ----------------
def panel_base(title_w=96):
    """위 패널(0..124): 제목 판·버튼 자리. 아래 패널(125..221): 플레이어 인벤토리('인벤토리' 글자가 y=128에 그려진다)."""
    im = Img(176, 222)
    copper_frame(im, 0, 0, 176, 125)
    for y in range(3, 122):  # 은은한 세로 그라데이션
        t = y / 122
        c = tuple(int(NAVY_1[i] * (1 - t) + NAVY_2[i] * t) for i in range(3))
        im.rect(3, y, 170, 1, c)
    # 제목 판(제목 글자가 그려지는 (8,6) 자리)
    im.rect(3, 3, title_w, 12, NAVY_0); im.rect(3, 15, title_w, 1, CU_DK); im.rect(3 + title_w, 3, 1, 13, CU_DK)
    copper_frame(im, 0, 125, 176, 97)
    im.rect(3, 128, 170, 91, NAVY_1)
    for r in range(3):
        for c in range(9): slot(im, 7 + c * 18, 139 + r * 18)
    for c in range(9): slot(im, 7 + c * 18, 197)
    return im

def chest_slot(im, col, row):
    slot(im, 7 + col * 18, 17 + row * 18)

MENU_BUTTON_ROW = 1  # 메인 메뉴 큰 버튼: 1~3줄, 열 0-2 / 3-5 / 6-8

def bg_menu():
    im = panel_base()
    icons = [icon_helmet(), icon_crystal(), icon_chest()]
    names = ['장비', '스탯', '보상']
    for n in range(3):
        x0 = 7 + n * 54; y0 = 17 + MENU_BUTTON_ROW * 18
        copper_frame(im, x0, y0, 54, 54)
        im.rect(x0 + 3, y0 + 3, 48, 48, NAVY_2)
        im.blit(icons[n], x0 + 3, y0 + 3)
        label(im, names[n], x0 + 27, y0 + 57)
    chest_slot(im, 8, 0)  # 닫기
    return im

STAT_SLOTS = [(1, 1), (1, 2), (1, 3), (1, 4), (5, 1), (5, 2), (5, 3)]  # (열, 줄) 아이콘, +버튼은 오른쪽 칸
SUMMARY_SLOT = (5, 4)

def bg_stats():
    im = panel_base(140)
    for c, r in STAT_SLOTS: chest_slot(im, c, r); chest_slot(im, c + 1, r)
    chest_slot(im, *SUMMARY_SLOT)
    chest_slot(im, 0, 0); chest_slot(im, 8, 0)  # 뒤로·닫기
    im.rect(87, 36, 1, 70, CU_DK)
    return im

EQUIP_ARMOR_COL, EQUIP_HAND_COL = 1, 7  # 방어구 1~4줄, 손 2~3줄

def bg_equipment():
    im = panel_base()
    sil = (40, 48, 86)
    im.ellipse(88, 34, 9, 10, sil); im.rect(75, 44, 26, 34, sil); im.rect(66, 46, 9, 28, sil); im.rect(101, 46, 9, 28, sil)
    im.rect(76, 78, 10, 34, sil); im.rect(90, 78, 10, 34, sil)
    for r in range(1, 5): chest_slot(im, EQUIP_ARMOR_COL, r)
    for r in (2, 3): chest_slot(im, EQUIP_HAND_COL, r)
    chest_slot(im, 0, 0); chest_slot(im, 8, 0)
    return im

# ---------------- HUD ----------------
HUD_W, HUD_H, HUD_STATES = 80, 9, 41

def hud_bar(fill, light, dark, empty):
    atlas = Img(HUD_W, HUD_H * HUD_STATES)
    inner = HUD_W - 6
    for s in range(HUD_STATES):
        y0 = s * HUD_H
        atlas.rect(0, y0, HUD_W, HUD_H, INK)
        atlas.rect(1, y0 + 1, HUD_W - 2, HUD_H - 2, CU)
        for i in range(1, HUD_W - 1): atlas.set(i, y0 + 1, CU_HI)
        atlas.rect(2, y0 + 2, HUD_W - 4, HUD_H - 4, CU_DK)
        atlas.rect(3, y0 + 3, inner, HUD_H - 6, empty)
        w = round(inner * s / (HUD_STATES - 1))
        if w:
            atlas.rect(3, y0 + 3, w, HUD_H - 6, fill)
            atlas.rect(3, y0 + 3, w, 1, light)
            atlas.rect(3, y0 + HUD_H - 4, w, 1, dark)
    return atlas

def hud_orb():
    im = Img(22, 22)
    im.ellipse(11, 11, 11, 11, INK); im.ellipse(11, 11, 10, 10, CU); im.ellipse(11, 10.5, 9, 9, CU_HI)
    im.ellipse(11, 11, 8.5, 8.5, CU_DK); im.ellipse(11, 11, 7.5, 7.5, NAVY_1); im.ellipse(11, 10, 6, 5, NAVY_3)
    return im

# ---------------- 폰트 ----------------
def pua(n): return chr(0xE000 + n)

ASCII_ROWS = []
for r in range(16):
    row = ''
    for c in range(16):
        code = r * 16 + c
        row += chr(code) if 0x20 <= code < 0x7F else '\u0000'
    ASCII_ROWS.append(row)

def text_font(ascent):
    return {"providers": [{"type": "bitmap", "file": "minecraft:font/ascii.png", "ascent": ascent, "chars": ASCII_ROWS}]}

def main():
    # 바닐라 HUD 숨김: 하트·방어구·배고픔·경험치바
    names = ['container', 'container_blinking', 'container_hardcore', 'container_hardcore_blinking', 'vehicle_container']
    for kind in ['full', 'half', 'hardcore_full', 'hardcore_half']:
        for pre in ['absorbing_', 'frozen_', 'poisoned_', 'withered_', '']:
            for blink in ['', '_blinking']: names.append(pre + kind + blink)
    names += ['vehicle_full', 'vehicle_half']
    for n in sorted(set(names)): Img(9, 9).save(f'assets/minecraft/textures/gui/sprites/hud/heart/{n}.png')
    for n in ['armor_empty', 'armor_full', 'armor_half', 'food_empty', 'food_empty_hunger', 'food_full', 'food_full_hunger',
              'food_half', 'food_half_hunger']:
        Img(9, 9).save(f'assets/minecraft/textures/gui/sprites/hud/{n}.png')
    for n in ['experience_bar_background', 'experience_bar_progress']:
        Img(182, 5).save(f'assets/minecraft/textures/gui/sprites/hud/{n}.png')

    # HUD
    hud_bar(RED, RED_HI, RED_DK, (40, 14, 20)).save('assets/rpg/textures/font/hud_hp.png')
    hud_bar(BLUE, BLUE_HI, BLUE_DK, (14, 18, 44)).save('assets/rpg/textures/font/hud_mp.png')
    hud_orb().save('assets/rpg/textures/font/hud_orb.png')
    write_json('assets/rpg/font/hud.json', {"providers": [
        {"type": "bitmap", "file": "rpg:font/hud_hp.png", "ascent": -26, "height": HUD_H, "chars": [pua(0x100 + i) for i in range(HUD_STATES)]},
        {"type": "bitmap", "file": "rpg:font/hud_mp.png", "ascent": -26, "height": HUD_H, "chars": [pua(0x140 + i) for i in range(HUD_STATES)]},
        {"type": "bitmap", "file": "rpg:font/hud_orb.png", "ascent": -19, "height": 22, "chars": [pua(0x180)]},
    ]})
    write_json('assets/rpg/font/hud_text.json', text_font(-27))
    write_json('assets/rpg/font/hud_points.json', text_font(-10))
    adv = {}
    for i in range(10):
        adv[pua(0x800 + i)] = -(1 << i)
        adv[pua(0x810 + i)] = (1 << i)
    write_json('assets/rpg/font/space.json', {"providers": [{"type": "space", "advances": adv}]})

    # GUI 배경
    bg_menu().save('assets/rpg/textures/font/gui_menu.png')
    bg_stats().save('assets/rpg/textures/font/gui_stats.png')
    bg_equipment().save('assets/rpg/textures/font/gui_equipment.png')
    write_json('assets/rpg/font/gui.json', {"providers": [
        {"type": "bitmap", "file": f"rpg:font/gui_{n}.png", "ascent": 13, "height": 222, "chars": [pua(i)]}
        for i, n in enumerate(['menu', 'stats', 'equipment'])]})

    # 아이템 아이콘(메뉴 버튼용)
    for n in ['blank', 'stat_strength', 'stat_agility', 'stat_resistance', 'stat_vitality', 'stat_focus', 'stat_luck', 'stat_spirit',
              'plus', 'back', 'close', 'summary']:
        icon16(n).save(f'assets/rpg/textures/item/{n}.png')
        write_json(f'assets/rpg/models/item/{n}.json', {"parent": "minecraft:item/generated", "textures": {"layer0": f"rpg:item/{n}"}})
        write_json(f'assets/rpg/items/{n}.json', {"model": {"type": "minecraft:model", "model": f"rpg:item/{n}"}})

    # 팩 정보·아이콘
    write_json('pack.mcmeta', {"pack": {"description": "MinecraftRPG UI", "pack_format": PACK_FORMAT,
                                        "min_format": PACK_FORMAT, "max_format": PACK_FORMAT}})
    icon = Img(64, 64); icon.rect(0, 0, 64, 64, NAVY_1); copper_frame(icon, 0, 0, 64, 64); icon.blit(icon_crystal(48), 8, 8)
    icon.save('pack.png')

if __name__ == '__main__':
    main()
    print('ok')
