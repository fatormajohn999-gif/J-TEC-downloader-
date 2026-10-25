"""Effect definitions for the renderer. To add a new look or effect, edit ONLY this file.

LOOKS   = one color look per video (a plain FFmpeg filter string)
EFFECTS = stackable effects; each is a function (w, h) -> filter string
"""

LOOKS = {
    # teal shadows, warm highlights, slight contrast, soft vignette
    "cinematic": "eq=contrast=1.15:saturation=1.1:gamma=0.95,"
                 "colorbalance=rs=-0.06:gs=-0.01:bs=0.08:rm=0.02:bm=0.03:rh=0.08:gh=0.02:bh=-0.07,"
                 "vignette=PI/5",
    # moody and dark: lower brightness, strong contrast, muted color
    "dark": "eq=brightness=-0.08:contrast=1.25:saturation=0.75:gamma=0.85,vignette=PI/4",
    # black and white, hard contrast
    "noir": "hue=s=0,eq=contrast=1.35:brightness=-0.06,vignette=PI/4",
    # lifted blacks, muted color (film / matte look)
    "faded": "curves=all='0/0.08 1/0.92',eq=saturation=0.85:contrast=0.95",
    "warm": "colorbalance=rs=0.08:gs=0.02:bs=-0.08:rh=0.05:bh=-0.05,eq=saturation=1.08",
    "cool": "colorbalance=rs=-0.06:bs=0.08:rh=-0.04:bh=0.06,eq=saturation=1.05",
    "bw": "hue=s=0,eq=contrast=1.1",
    "vivid": "eq=saturation=1.4:contrast=1.06",
}

EFFECTS = {
    # film grain
    "grain": lambda w, h: "noise=alls=14:allf=t+u",
    # crisper detail
    "sharpen": lambda w, h: "unsharp=5:5:0.9:5:5:0.0",
    # black bars top and bottom (covers 10% of the picture each)
    "bars": lambda w, h: ("drawbox=x=0:y=0:w=iw:h=ih*0.1:color=black:t=fill,"
                          "drawbox=x=0:y=ih*0.9:w=iw:h=ih*0.1:color=black:t=fill"),
    # handheld "walking" camera shake (small, smooth)
    "shake": lambda w, h: (
        f"scale=trunc({w}*1.06/2)*2:trunc({h}*1.06/2)*2,"
        f"crop={w}:{h}:x='(iw-{w})/2+sin(t*2.3)*(iw-{w})/2':y='(ih-{h})/2+sin(t*1.9+1)*(ih-{h})/2'"),
    # gentle rhythmic zoom in and out
    "pulse": lambda w, h: (
        f"scale=w='trunc({w}*(1.05+0.04*sin(2*PI*t*0.8))/2)*2':"
        f"h='trunc({h}*(1.05+0.04*sin(2*PI*t*0.8))/2)*2':eval=frame,crop={w}:{h}"),
}
