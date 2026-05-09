from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger

from app.services.memory_service import compress_all_devices
from app.db import cleanup_chat_logs

scheduler = BackgroundScheduler()


def start_scheduler():
    scheduler.add_job(
        compress_all_devices,
        trigger=CronTrigger(hour=2, minute=0),
        id="memory_compress",
        name="Compress daily memories",
        replace_existing=True,
    )
    scheduler.add_job(
        cleanup_chat_logs,
        trigger=CronTrigger(hour=3, minute=0),
        id="chat_logs_cleanup",
        name="Cleanup old chat logs",
        replace_existing=True,
    )
    scheduler.start()
    print("[scheduler] Started daily memory compression at 02:00, chat_logs cleanup at 03:00")


def shutdown_scheduler():
    scheduler.shutdown()
