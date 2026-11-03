"""Bounded, ordered worker scheduling for archive data operations."""
from __future__ import annotations

from collections import deque
from collections.abc import Callable, Iterable, Iterator
from concurrent.futures import Future, ThreadPoolExecutor
from typing import TypeVar

T = TypeVar("T")
R = TypeVar("R")


def bounded_ordered_map(
    function: Callable[[T], R],
    items: Iterable[T],
    *,
    workers: int = 1,
    max_pending: int | None = None,
) -> Iterator[R]:
    """Map work in parallel while preserving order and bounding queued inputs.

    At most ``max_pending`` items (and their associated memory) are in flight.
    Exceptions are re-raised when the corresponding result is yielded.
    """
    if not isinstance(workers, int) or isinstance(workers, bool) or workers < 1:
        raise ValueError("workers must be a positive integer")
    if max_pending is None:
        max_pending = max(1, workers * 2)
    if not isinstance(max_pending, int) or isinstance(max_pending, bool) or max_pending < 1:
        raise ValueError("max_pending must be a positive integer")
    if workers == 1:
        for item in items:
            yield function(item)
        return

    iterator = iter(items)
    pending: deque[Future[R]] = deque()
    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="qamzor") as executor:
        for _ in range(max_pending):
            try:
                pending.append(executor.submit(function, next(iterator)))
            except StopIteration:
                break
        while pending:
            future = pending.popleft()
            result = future.result()
            try:
                pending.append(executor.submit(function, next(iterator)))
            except StopIteration:
                pass
            yield result
