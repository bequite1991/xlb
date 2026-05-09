import os

from fastapi import APIRouter

from app.config import PUBLIC_HOST

router = APIRouter(prefix="/api/ota", tags=["OTA"])

APK_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "static")
LATEST_VERSION_CODE = 59  # 手动递增此值后上传新 APK


@router.get("/check")
def ota_check(version_code: int = 0, device_id: str = ""):
    if version_code >= LATEST_VERSION_CODE:
        return {"latest_version": LATEST_VERSION_CODE, "download_url": None, "changelog": ""}

    apk_url = f"{PUBLIC_HOST}/app-debug.apk" if os.path.exists(os.path.join(APK_DIR, "app-debug.apk")) else None
    return {
        "latest_version": LATEST_VERSION_CODE,
        "download_url": apk_url,
        "changelog": "OTA update available",
    }
