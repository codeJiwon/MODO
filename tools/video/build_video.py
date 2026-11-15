#!/usr/bin/env python3
"""
MODO 시연 영상 합성 스크립트.

입력: Pleos Connect Emulator에서 `adb exec-out screencap`으로 캡처한 프레임(파일명 f_<epoch초>.png)과
      장면 전환 시각 기록(marks.txt). 이 환경의 에뮬레이터는 소프트웨어 렌더링이라 screenrecord가
      프레임을 받지 못해, 실제 화면을 연속 캡처해 영상으로 만든다(약 1.2fps → 30fps 디졸브 보간).
출력: 1920x1080 H.264 MP4 + 한국어 내레이션(macOS `say` Yuna) + 자막/구분 표시를 프레임에 직접 렌더링.

사용: python3 build_video.py <capture_dir> <out.mp4>
  capture_dir/main_frames, main_marks.txt, sdk_frames, sdk_marks.txt
"""
import glob
import os
import subprocess
import sys
import textwrap

from PIL import Image, ImageDraw, ImageFilter, ImageFont

CAP, OUT = sys.argv[1], sys.argv[2]
WORK = os.path.join(CAP, "build")
os.makedirs(WORK, exist_ok=True)

W, H = 1920, 1080
NAVY = (7, 13, 26)
SURFACE = (14, 24, 43)
OUTLINE = (38, 54, 90)
TEXT = (234, 240, 255)
MUTED = (147, 163, 198)
FAINT = (94, 110, 145)
BLUE = (61, 139, 255)
MINT = (61, 219, 181)
AMBER = (255, 181, 71)
FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"


def font(size, weight="Regular"):
    return ImageFont.truetype(FONT, size, index={"Regular": 0, "Medium": 2, "SemiBold": 4, "Bold": 6, "Light": 8}[weight])


def load_frames(name):
    frames = []
    for p in sorted(glob.glob(os.path.join(CAP, f"{name}_frames", "f_*.png"))):
        ts = float(os.path.basename(p)[2:-4])
        if os.path.getsize(p) > 50_000:  # 캡처 실패(검은 화면) 제외
            frames.append((ts, p))
    marks = {}
    for line in open(os.path.join(CAP, f"{name}_marks.txt")):
        t, k = line.split()
        marks[k] = float(t)
    t0 = marks["start"]
    return [(ts - t0, p) for ts, p in frames], {k: v - t0 for k, v in marks.items()}


MAIN, MM = load_frames("main")
SDK, SM = load_frames("sdk")

DEMO_BADGE = ("DEMO · 시뮬레이션 데이터", AMBER)
SDK_BADGE = ("실제 Pleos SDK 연동", MINT)

SEGMENTS = [
    dict(kind="card", card="intro", sub="친구들과 함께 떠난 여행. 하지만 이동 중 계획이 달라진다면?",
         narr="친구 여덟 명이 차 세 대로 강릉 여행을 떠납니다. 그런데 달리는 중에 계획이 바뀌면, 어떻게 모두가 함께 움직일 수 있을까요?"),
    dict(src="main", ranges=[(0.3, MM["tap_demo"])], step="01", title="Welcome", en="MODO 시작 화면",
         badge=("앱 실제 실행 화면", BLUE), sub="MODO — Move On, Drive On",
         desc="Kotlin · Jetpack Compose로 만든 Android 앱을 Pleos Connect Emulator v2.1.0에서 실행한 화면입니다.",
         narr="MODO는 여러 차량이 함께 이동하도록 돕는 그룹 드라이빙 코파일럿입니다."),
    dict(src="main", ranges=[(MM["tap_demo"] + 0.3, MM["scene_2"])], step="02", title="Journey Room", en="그룹 현황",
         badge=DEMO_BADGE, sub="함께 이동하는 새로운 방법.",
         desc="차량 B·C의 행동과 모든 차량의 위치는 데모용 시뮬레이션 데이터입니다. 화면 왼쪽 아래에도 DEMO 표시가 있습니다.",
         narr="데모 모드에서 세 대의 차량이 각자 강릉으로 이동합니다. 다른 차량과 위치는 시뮬레이션 데이터입니다."),
    dict(src="main", ranges=[(MM["scene_2"], MM["scene_3"])], step="03", title="Mobility Intent", en="이동 요청",
         badge=DEMO_BADGE, sub="말 한마디로 전달되는 이동 요청.",
         desc="차량 B의 음성 입력 장면은 정해진 문장으로 재현했습니다. 문장을 '휴식 요청(REST_REQUEST)'으로 분류해 그룹에 공유하는 처리는 실제 앱 로직입니다.",
         narr="B 차량에서 '우리 잠깐 쉬었다 가자'라고 말하면, MODO가 휴식 요청으로 이해하고 그룹에 바로 알립니다."),
    dict(src="main", ranges=[(MM["scene_3"], MM["scene_5"])], step="04", title="Smart Rendezvous", en="합류 장소 제안과 승인",
         badge=DEMO_BADGE, sub="여러 차량의 결정을 하나로.",
         desc="A가 횡성휴게소를 제안하고, B·C의 동의(시뮬레이션)가 모여야 제안이 확정(APPROVED)됩니다.",
         narr="A 차량이 횡성휴게소를 합류 장소로 제안합니다. 모든 차량이 동의해야 합류 장소가 확정됩니다."),
    dict(src="main", ranges=[(MM["scene_5"], MM["scene_6"])], step="05", title="Group Route Action", en="내비게이션 적용 상태",
         badge=DEMO_BADGE, sub="결정에서 실제 이동까지.",
         desc="'그룹 합의 완료'와 차량별 '실제 적용'을 따로 표시합니다. A·B 적용 완료, C는 운전자 확인 대기(시뮬레이션).",
         narr="합의가 끝나면 각 차량 내비게이션에 경로 변경을 요청하고, 실제로 적용됐는지 차량마다 따로 확인합니다."),
    dict(src="sdk", ranges=[(0.0, 3.6), (SM["scene_5"] + 1.0, SM["back_to_modo"]), (SM["back_to_modo"] + 2.0, SM["end"])],
         step="SDK", title="NaviHelper 실제 연동", en="Pleos Navigation 경로 요청",
         badge=SDK_BADGE, sub="실제 Pleos NaviHelper SDK로 경로를 요청하고 적용을 확인합니다.",
         desc="내 차량(A)만 실제 SDK 사용: requestRoute → 내비게이션 앱이 횡성휴게소 안내 시작 → getDestinationInfo로 목적지 일치 확인 → 적용 완료. (지도 미표시는 외장 GPU가 없는 에뮬레이터 제약)",
         narr="이 장면은 실제 Pleos 내비게이션 SDK입니다. MODO의 요청으로 내비게이션이 횡성휴게소 안내를 시작하고, MODO는 목적지가 실제로 바뀌었는지 다시 확인한 뒤 적용 완료로 표시합니다."),
    dict(src="main", ranges=[(MM["scene_6"], MM["scene_7"])], step="06", title="Group Recovery", en="합류 실패와 재조율",
         badge=DEMO_BADGE, sub="계획이 어긋나도, 다시 함께.",
         desc="차량 C가 합류 장소를 지나치는 상황은 시뮬레이션 이벤트입니다. 재조율 세션 생성과 다음 휴게소 추천은 실제 앱 로직입니다.",
         narr="C 차량이 휴게소를 지나쳤습니다. MODO는 아직 지나지 않은 다음 휴게소를 새 합류 장소로 추천합니다."),
    dict(src="main", ranges=[(MM["scene_7"], MM["scene_8"])], step="07", title="Replan", en="새 합류 계획 확정",
         badge=DEMO_BADGE, sub="새로운 합류 계획이 확정되었어요.",
         desc="새 계획 #2(평창휴게소)는 기존 계획 #1과 구분되어 기록되고, 각 차량의 경로 적용 상태가 다시 갱신됩니다.",
         narr="새 합류 계획에 모두 동의하면, 이전 계획과 구분해 각 차량의 경로를 다시 맞춥니다."),
    dict(src="main", ranges=[(MM["scene_8"], MM["scene_9"] - 2.0), (MM["scene_9"], MM["scene_9"] + 9.0)],
         step="08", title="Journey Completed", en="합류와 도착",
         badge=DEMO_BADGE, sub="따로 달려도, 함께 도착하는 여행.",
         desc="도착은 GPS만으로 판단하지 않고 각 차량의 사용자 확인으로 기록합니다.",
         narr="세 차량이 다시 만나고, 모두 강릉에 도착했습니다. 따로 달려도, 함께 도착하는 여행."),
    dict(kind="card", card="outro", sub="MODO — Move On, Drive On", narr="모도. 무브 온, 드라이브 온."),
]


def say(text, path):
    aiff = path.replace(".wav", ".aiff")
    subprocess.run(["say", "-v", "Yuna", "-r", "182", "-o", aiff, text], check=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", aiff, "-ar", "48000", "-ac", "2", path], check=True)
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                         capture_output=True, text=True, check=True)
    return float(out.stdout.strip())


def wrap(draw, text, fnt, width):
    lines, line = [], ""
    for ch in text:
        if draw.textlength(line + ch, font=fnt) > width and line:
            lines.append(line)
            line = ch.lstrip() if ch == " " else ch
        else:
            line += ch
    if line:
        lines.append(line)
    return lines


def crop_app(p):
    img = Image.open(p).convert("RGB")
    app = img.crop((853, 0, 2560, 1300))  # Pleos 앱 창 + 상단 상태바
    scale = min(1220 / app.width, 930 / app.height)
    return app.resize((int(app.width * scale), int(app.height * scale)), Image.LANCZOS)


def subtitle(draw, text):
    f = font(40, "Bold")
    tw = draw.textlength(text, font=f)
    x, y = (W - tw) / 2, 990
    for dx, dy in ((2, 2), (0, 2), (2, 0)):
        draw.text((x + dx, y + dy), text, font=f, fill=(0, 0, 0))
    draw.text((x, y), text, font=f, fill=TEXT)


def composite(seg, frame_path):
    canvas = Image.new("RGB", (W, H), NAVY)
    d = ImageDraw.Draw(canvas)
    app = crop_app(frame_path)
    ax, ay = 660, 22
    d.rounded_rectangle((ax - 3, ay - 3, ax + app.width + 2, ay + app.height + 2), radius=10, outline=OUTLINE, width=3)
    canvas.paste(app, (ax, ay))
    # 왼쪽 설명 패널
    x = 48
    d.text((x, 40), "MODO MVP 시연", font=font(26, "SemiBold"), fill=MUTED)
    d.text((x, 76), "팀 정지원 · Pleos Connect Emulator v2.1.0", font=font(20), fill=FAINT)
    d.text((x, 150), seg["step"], font=font(72, "Bold"), fill=BLUE)
    d.text((x, 240), seg["title"], font=font(44, "Bold"), fill=TEXT)
    d.text((x, 300), seg["en"], font=font(28, "Medium"), fill=MUTED)
    label, color = seg["badge"]
    bf = font(26, "Bold")
    bw = d.textlength(label, font=bf)
    d.rounded_rectangle((x, 370, x + bw + 40, 422), radius=26, fill=tuple(int(c * 0.18) for c in color), outline=color, width=2)
    d.text((x + 20, 380), label, font=bf, fill=color)
    y = 460
    for line in wrap(d, seg["desc"], font(25), 560):
        d.text((x, y), line, font=font(25), fill=TEXT)
        y += 38
    d.text((x, 920), "장면마다 위 배지로 실제 SDK 연동과 시뮬레이션을 구분합니다.", font=font(18), fill=FAINT)
    subtitle(d, seg["sub"])
    return canvas


def card(seg):
    if seg["card"] == "intro":
        bg = Image.open(MAIN[2][1]).convert("RGB").crop((853, 70, 2540, 1300)).resize((W, H)).filter(ImageFilter.GaussianBlur(18))
    else:
        bg = Image.open(MAIN[-1][1]).convert("RGB").crop((853, 70, 2540, 1300)).resize((W, H)).filter(ImageFilter.GaussianBlur(18))
    canvas = Image.blend(bg, Image.new("RGB", (W, H), NAVY), 0.72)
    d = ImageDraw.Draw(canvas)

    def center(text, y, fnt, fill):
        d.text(((W - d.textlength(text, font=fnt)) / 2, y), text, font=fnt, fill=fill)

    if seg["card"] == "intro":
        center("친구 8명, 차량 3대, 목적지 강릉", 330, font(40, "Medium"), MUTED)
        center("따로 달리는 차량들이", 420, font(72, "Bold"), TEXT)
        center("함께 움직이려면?", 510, font(72, "Bold"), TEXT)
        center("이 영상의 다른 차량 행동·위치는 시뮬레이션이며, 실제 SDK 연동 장면은 별도로 표시합니다.", 700, font(26), AMBER)
    else:
        center("MODO", 300, font(140, "Bold"), TEXT)
        center("Move On, Drive On", 470, font(48, "Light"), BLUE)
        center("따로 달려도, 함께 도착하는 여행.", 560, font(44, "Medium"), MUTED)
        center("팀 정지원", 700, font(32, "SemiBold"), TEXT)
        center("실제 SDK 연동: NaviHelper(경로 요청·적용 확인) · FusedLocation · STT/TTS 초기화", 790, font(24), MINT)
        center("시뮬레이션: 차량 B·C 행동과 위치, B·C 경로 결과, 음성 입력 문장, 음성 안내(자막)", 830, font(24), AMBER)
    subtitle(d, seg["sub"])
    return canvas


def frames_in(src, ranges):
    frames = MAIN if src == "main" else SDK
    out = []
    for a, b in ranges:
        sel = [(t, p) for t, p in frames if a <= t < b]
        before = [(t, p) for t, p in frames if t < a]
        if before:  # 구간 시작 시점의 화면
            sel = [(a, before[-1][1])] + sel
        out.append([(t - a, p) for t, p in sel] + [(b - a, None)])
    return out


concat_lines, audio_parts = [], []
n = 0
total = 0.0
for i, seg in enumerate(SEGMENTS):
    wav = os.path.join(WORK, f"narr_{i:02d}.wav")
    nd = say(seg["narr"], wav)
    target = nd + 1.4  # 내레이션 + 여유
    if seg.get("kind") == "card":
        img = card(seg)
        path = os.path.join(WORK, f"c_{n:04d}.png"); n += 1
        img.save(path)
        dur = max(target, 6.0)
        concat_lines += [f"file '{path}'", f"duration {dur:.3f}"]
    else:
        parts = frames_in(seg["src"], seg["ranges"])
        visual = sum(p[-1][0] for p in parts)
        speed = max(1.0, visual / target)
        dur = visual / speed
        for part in parts:
            for (t, p), (t2, _) in zip(part[:-1], part[1:]):
                img = composite(seg, p)
                path = os.path.join(WORK, f"c_{n:04d}.png"); n += 1
                img.save(path)
                concat_lines += [f"file '{path}'", f"duration {max((t2 - t) / speed, 0.05):.3f}"]
        if dur < target:  # 내레이션이 더 길면 마지막 화면 유지
            concat_lines += [f"file '{path}'", f"duration {target - dur:.3f}"]
            dur = target
    seg_wav = os.path.join(WORK, f"seg_{i:02d}.wav")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-af", f"adelay=350|350,apad=whole_dur={dur:.3f}",
                    "-t", f"{dur:.3f}", seg_wav], check=True)
    audio_parts.append(seg_wav)
    print(f"segment {i:02d} {seg.get('title', seg.get('card'))}: {dur:.1f}s (narration {nd:.1f}s)")
    total += dur

concat_lines.append(concat_lines[-2])  # concat demuxer: 마지막 파일 반복
with open(os.path.join(WORK, "frames.txt"), "w") as f:
    f.write("\n".join(concat_lines) + "\n")
with open(os.path.join(WORK, "audio.txt"), "w") as f:
    f.write("\n".join(f"file '{p}'" for p in audio_parts) + "\n")

subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", os.path.join(WORK, "audio.txt"),
                "-c:a", "pcm_s16le", os.path.join(WORK, "narration.wav")], check=True)
subprocess.run([
    "ffmpeg", "-v", "error", "-y",
    "-f", "concat", "-safe", "0", "-i", os.path.join(WORK, "frames.txt"),
    "-i", os.path.join(WORK, "narration.wav"),
    "-vf", "fps=30,format=yuv420p",
    "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-tune", "stillimage",
    "-c:a", "aac", "-b:a", "160k", "-shortest", "-movflags", "+faststart", OUT,
], check=True)
print(f"done: {OUT} ({total:.1f}s)")
