from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from app.settings import settings


@dataclass(frozen=True)
class StoredTask:
    task_id: str
    knowledge_base_id: int
    object_key: str
    filename: str
    content_type: str
    status: str
    failure_reason: str | None
    retry_count: int
    max_retries: int
    replace_existing: bool
    created_at: datetime | None = None
    updated_at: datetime | None = None


class MySqlTaskStore:
    def _connect(self):
        import pymysql

        return pymysql.connect(
            host=settings.mysql_host,
            port=settings.mysql_port,
            user=settings.mysql_user,
            password=settings.mysql_password,
            database=settings.mysql_database,
            autocommit=True,
            cursorclass=pymysql.cursors.DictCursor,
            connect_timeout=5,
            read_timeout=10,
            write_timeout=10,
        )

    def create(self, task: StoredTask) -> None:
        connection = self._connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    INSERT INTO document_processing_task
                        (task_id, knowledge_base_id, object_key, filename, content_type,
                         status, failure_reason, retry_count, max_retries, replace_existing)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    """,
                    (
                        task.task_id,
                        task.knowledge_base_id,
                        task.object_key,
                        task.filename,
                        task.content_type,
                        task.status,
                        task.failure_reason,
                        task.retry_count,
                        task.max_retries,
                        task.replace_existing,
                    ),
                )
        finally:
            connection.close()

    def get(self, task_id: str) -> StoredTask | None:
        connection = self._connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT task_id, knowledge_base_id, object_key, filename, content_type, "
                    "status, failure_reason, retry_count, max_retries, replace_existing, "
                    "created_at, updated_at FROM document_processing_task WHERE task_id = %s",
                    (task_id,),
                )
                row = cursor.fetchone()
        finally:
            connection.close()
        return self._from_row(row) if row else None

    def claim(self, task_id: str) -> bool:
        connection = self._connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE document_processing_task
                    SET status = 'PROCESSING'
                    WHERE task_id = %s
                      AND (
                          status IN ('ACCEPTED', 'RETRYING')
                          OR (status = 'PROCESSING' AND TIMESTAMPDIFF(SECOND, updated_at, NOW()) >= %s)
                      )
                    """,
                    (task_id, settings.task_processing_stale_seconds),
                )
                return cursor.rowcount == 1
        finally:
            connection.close()

    def update(self, task_id: str, status: str, failure_reason: str | None, retry_count: int) -> StoredTask | None:
        connection = self._connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "UPDATE document_processing_task SET status = %s, failure_reason = %s, "
                    "retry_count = %s WHERE task_id = %s",
                    (status, failure_reason, retry_count, task_id),
                )
        finally:
            connection.close()
        return self.get(task_id)

    def retry(self, task_id: str) -> StoredTask | None:
        connection = self._connect()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "UPDATE document_processing_task SET status = 'ACCEPTED', failure_reason = NULL, "
                    "retry_count = 0, replace_existing = TRUE WHERE task_id = %s AND status = 'FAILED'",
                    (task_id,),
                )
        finally:
            connection.close()
        return self.get(task_id)

    def find_recoverable(self) -> list[StoredTask]:
        connection = self._connect()
        try:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        SELECT task_id, knowledge_base_id, object_key, filename, content_type,
                               status, failure_reason, retry_count, max_retries, replace_existing,
                               created_at, updated_at
                        FROM document_processing_task
                        WHERE status IN ('ACCEPTED', 'RETRYING')
                           OR (status = 'PROCESSING' AND TIMESTAMPDIFF(SECOND, updated_at, NOW()) >= %s)
                        ORDER BY created_at
                        LIMIT 20
                        """,
                        (settings.task_processing_stale_seconds,),
                    )
                    rows = cursor.fetchall()
            except Exception as exception:
                if not exception.args or exception.args[0] != 1146:
                    raise
                rows = []
        finally:
            connection.close()
        return [self._from_row(row) for row in rows]

    def _from_row(self, row: dict[str, object]) -> StoredTask:
        return StoredTask(
            task_id=str(row["task_id"]),
            knowledge_base_id=int(row["knowledge_base_id"]),
            object_key=str(row["object_key"]),
            filename=str(row["filename"]),
            content_type=str(row["content_type"]),
            status=str(row["status"]),
            failure_reason=row["failure_reason"],
            retry_count=int(row["retry_count"]),
            max_retries=int(row["max_retries"]),
            replace_existing=bool(row["replace_existing"]),
            created_at=row["created_at"],
            updated_at=row["updated_at"],
        )


task_store = MySqlTaskStore()
