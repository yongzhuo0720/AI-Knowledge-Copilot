"""``MySqlTaskStore`` 的续期（心跳）相关行为。

这里不连真库：``_connect`` 被替换成一个假连接，验证的是**发出去的语句本身**
是否正确 —— 具体说就是「updated_at 有没有被显式推进」。这一点必须用测试钉住，
因为 MySQL 的 ``ON UPDATE CURRENT_TIMESTAMP`` 不会替我们做这件事（见下面的用例）。
"""

import pytest

from app.settings import settings
from app.task_store import MySqlTaskStore


class FakeDb:
    """记录每一次 ``_connect`` 与每一条执行过的语句。"""

    def __init__(self) -> None:
        self.connections: list[object] = []
        self.statements: list[tuple[str, tuple[object, ...]]] = []
        self.rowcount = 1
        self.rows: list[dict[str, object]] = []

    def connect(self):
        db = self

        class Cursor:
            @property
            def rowcount(self) -> int:
                return db.rowcount

            def execute(self, sql: str, params: tuple[object, ...] = ()) -> None:
                db.statements.append((" ".join(sql.split()), tuple(params)))

            def fetchall(self) -> list[dict[str, object]]:
                return list(db.rows)

            def fetchone(self) -> dict[str, object] | None:
                return db.rows[0] if db.rows else None

            def __enter__(self) -> "Cursor":
                return self

            def __exit__(self, *_exc_info: object) -> None:
                return None

        class Connection:
            def __init__(self) -> None:
                self.closed = False

            def cursor(self) -> Cursor:
                return Cursor()

            def close(self) -> None:
                self.closed = True

        connection = Connection()
        self.connections.append(connection)
        return connection


@pytest.fixture
def fake_db(monkeypatch) -> FakeDb:
    db = FakeDb()
    monkeypatch.setattr(MySqlTaskStore, "_connect", lambda self: db.connect())
    return db


def test_claim_explicitly_advances_updated_at(fake_db: FakeDb) -> None:
    """抢到任务时必须显式写 updated_at。

    如果只写 ``SET status = 'PROCESSING'``，而这一行本来就是 PROCESSING，
    MySQL 会认为「没有任何列发生变化」，``ON UPDATE CURRENT_TIMESTAMP`` 不会触发，
    时间戳原地不动 —— 于是一个正在被处理的任务会一直「看起来」是僵尸任务，
    每个恢复周期都被重新派发一次。
    """
    assert MySqlTaskStore().claim("task-1") is True

    sql, params = fake_db.statements[0]
    assert "SET status = 'PROCESSING', updated_at = NOW()" in sql
    assert params == ("task-1", settings.task_processing_stale_seconds)


def test_claim_still_refuses_a_fresh_processing_task(fake_db: FakeDb) -> None:
    """僵尸判定的条件本身没有被削弱：太新的 PROCESSING 任务依旧抢不走。"""
    fake_db.rowcount = 0

    assert MySqlTaskStore().claim("task-1") is False

    sql, _ = fake_db.statements[0]
    assert "status IN ('ACCEPTED', 'RETRYING')" in sql
    assert "status = 'PROCESSING' AND TIMESTAMPDIFF(SECOND, updated_at, NOW()) >= %s" in sql


def test_claim_closes_its_connection(fake_db: FakeDb) -> None:
    MySqlTaskStore().claim("task-1")

    assert fake_db.connections[0].closed is True


def test_touch_refreshes_only_processing_tasks(fake_db: FakeDb) -> None:
    """续期只能作用于「处理中」的任务。

    已完成 / 已失败的任务不该被心跳刷成「新鲜」的 —— 否则一个残留的心跳线程
    就能让一条终态记录的时间戳一直往前走，掩盖真实的活动轨迹。
    """
    MySqlTaskStore().touch("task-1")

    sql, params = fake_db.statements[0]
    assert sql == (
        "UPDATE document_processing_task SET updated_at = NOW() "
        "WHERE task_id = %s AND status = 'PROCESSING'"
    )
    assert params == ("task-1",)


def test_touch_opens_a_fresh_connection_for_every_beat(fake_db: FakeDb) -> None:
    """心跳由后台线程按节奏打，不能与主线程共用连接（pymysql 连接不是线程安全的）。"""
    store = MySqlTaskStore()

    store.touch("task-1")
    store.touch("task-1")

    assert len(fake_db.connections) == 2
    assert all(connection.closed for connection in fake_db.connections)


def test_find_recoverable_takes_the_oldest_first(fake_db: FakeDb) -> None:
    """恢复顺序「先来先服务」，避免老任务被源源不断的新任务饿死。"""
    assert MySqlTaskStore().find_recoverable() == []

    sql, params = fake_db.statements[0]
    assert "ORDER BY created_at" in sql
    assert "LIMIT 20" in sql
    assert params == (settings.task_processing_stale_seconds,)
