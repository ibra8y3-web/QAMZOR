# QAMZOR Job Center API

`JobCenter` runs archive creation in a bounded thread pool and persists secret-free progress records.

```python
from qamzor import JobCenter

center = JobCenter("./.qamzor-jobs", workers=2)
job_id = center.submit_create_archive(
    "release.qzra",
    ["./src", "./docs"],
    profile="balanced",
    workers=4,
    # password="...",  # accepted for the operation; never persisted in job JSON
)

print(center.get(job_id))
center.pause(job_id)
center.resume(job_id)
center.cancel(job_id)
result = center.wait(job_id, timeout=3600)
center.shutdown()
```

States: `queued`, `running`, `pause_requested`, `paused`, `completed`, `cancelled`, `failed`; stale active states become `interrupted` after process restart. Pause/cancel is cooperative and checked between input chunks. An in-progress archive is published only after the full manifest/payload has been written and synced; cancellation before publication removes the temporary output.

The Job Center does not yet coordinate multiple job types (verify/extract/recovery), survive process termination by resuming an in-flight job, or expose an Android foreground-service notification. Applications must keep the JobCenter instance alive while jobs run and close it during normal shutdown.
