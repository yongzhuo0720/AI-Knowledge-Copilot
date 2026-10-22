"""对象存储访问。

单独成模块而不是留在 ``processing.py`` 里：解析任务和 Agent 工具都要读**原始文档**
（``document_outline`` 需要重新解析文档结构来还原小节标题），把下载动作收在一处，
两边就不会各写一份 Minio 客户端构造与连接释放的代码。

只做「按 object_key 取回字节」这一件事，不含任何格式判断 —— 那属于
:mod:`app.document_blocks` 的职责。
"""


def download_object(object_key: str) -> bytes:
    """从对象存储取回对象内容。

    调用方负责确保 object_key 存在；不存在时 Minio 会抛 ``S3Error``，
    由上层的错误处理统一转成可读信息。
    """
    from minio import Minio

    from app.settings import settings

    client = Minio(
        settings.minio_endpoint,
        access_key=settings.minio_access_key,
        secret_key=settings.minio_secret_key,
        secure=False,
    )
    response = client.get_object(settings.minio_bucket, object_key)
    try:
        return response.read()
    finally:
        # 必须显式释放，否则连接不会归还连接池，高并发下会堆积
        response.close()
        response.release_conn()
