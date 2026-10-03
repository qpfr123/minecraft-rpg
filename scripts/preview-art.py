#!/usr/bin/env python3
"""리소스팩 그림 미리보기(build/art-preview). 게임 화면 배치를 흉내 내 확대 저장한다."""
import importlib.util, os, sys
here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('gen', os.path.join(here, 'gen-art.py')); gen = importlib.util.module_from_spec(spec); spec.loader.exec_module(gen)
Img = gen.Img
OUT = os.path.join(here, '..', 'build', 'art-preview'); os.makedirs(OUT, exist_ok=True)

def scale(im, k):
    o = Img(im.w * k, im.h * k)
    for y in range(im.h):
        for x in range(im.w):
            c = im.px[y][x]
            if c[3]: o.rect(x * k, y * k, k, k, c)
    return o

def save(im, name):
    gen.ROOT = OUT
    im.save(name)

# 메뉴 3종 + 아이콘을 슬롯에 얹은 모습
screens = Img(176 * 3 + 16, 222)
screens.rect(0, 0, screens.w, screens.h, (60, 70, 60))
for i, b in enumerate([gen.bg_menu(), gen.bg_stats(), gen.bg_equipment()]): screens.blit(b, i * 184, 0)
# 스탯 화면 아이콘 배치 흉내
stats = ['stat_strength', 'stat_agility', 'stat_resistance', 'stat_vitality', 'stat_focus', 'stat_luck', 'stat_spirit']
for n, s in enumerate(stats):
    col, row = gen.STAT_SLOTS[n]
    screens.blit(gen.icon16(s), 184 + 8 + col * 18, 18 + row * 18)
    screens.blit(gen.icon16('plus'), 184 + 8 + (col + 1) * 18, 18 + row * 18)
screens.blit(gen.icon16('summary'), 184 + 8 + gen.SUMMARY_SLOT[0] * 18, 18 + gen.SUMMARY_SLOT[1] * 18)
for b in (0, 184, 368): screens.blit(gen.icon16('close'), b + 8 + 8 * 18, 18)
for b in (184, 368): screens.blit(gen.icon16('back'), b + 8, 18)
save(scale(screens, 3), 'screens.png')

# HUD: 핫바 위 배치
hud = Img(240, 60); hud.rect(0, 0, 240, 60, (90, 110, 140))
hb = Img(182, 22); hb.rect(0, 0, 182, 22, (40, 40, 40)); hb.frame(0, 0, 182, 22, (0, 0, 0))
for i in range(9): hb.frame(1 + i * 20, 1, 20, 20, (120, 120, 120))
x0 = 29; hud.blit(hb, x0, 38)
hp = gen.hud_bar(gen.RED, gen.RED_HI, gen.RED_DK, (40, 14, 20)); mp = gen.hud_bar(gen.BLUE, gen.BLUE_HI, gen.BLUE_DK, (14, 18, 44))
def state(atlas, s):
    o = Img(80, 9)
    for y in range(9):
        for x in range(80): o.px[y][x] = atlas.px[s * 9 + y][x]
    return o
# 레이아웃: HP x -91..-11, 오브 -11..11, MP 11..91 (가운데 = x0+91), 위쪽 = 바닥-39
cx = x0 + 91; top = 60 - 39
hud.blit(state(hp, 30), cx - 91, top); hud.blit(gen.hud_orb(), cx - 11, 60 - 46); hud.blit(state(mp, 20), cx + 11, top)
save(scale(hud, 4), 'hud.png')
print(OUT)
