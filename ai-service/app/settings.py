from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "ai-knowledge-copilot-ai-service"
    app_version: str = "0.0.1"
    host: str = "0.0.0.0"
    port: int = 8000
    minio_endpoint: str = "localhost:9000"
    minio_access_key: str = "minioadmin"
    minio_secret_key: str = "change-me-minio"
    minio_bucket: str = "ai-copilot"
    milvus_host: str = "localhost"
    milvus_port: int = 19530
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_database: str = "ai_copilot"
    mysql_user: str = "ai_copilot"
    mysql_password: str = ""
    task_store_enabled: bool = False
    task_max_retries: int = 3
    task_retry_backoff_seconds: float = 0.1
    task_recovery_interval_seconds: int = 5
    task_processing_stale_seconds: int = 300
    embedding_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    embedding_api_key: str = ""
    embedding_model: str = "text-embedding-v4"
    chat_base_url: str = "https://api.deepseek.com"
    chat_api_key: str = ""
    chat_model: str = "deepseek-chat"

    # ---- 内部鉴权 ----
    # 这个服务原本假设「只有 backend 能访问它」，但 8000 端口是发布到宿主机的，
    # 任何人都能直接调用解析、检索和问答接口。所有非健康检查接口现在都要求
    # X-Internal-Token。留空表示不校验（仅限本地开发与测试），
    # 容器部署必须在 deploy/.env 里配置 —— 否则启动日志里会有明确警告。
    internal_token: str = ""

    # ---- 检索 ----
    # vector: 纯向量 | keyword: 纯 BM25 | hybrid: 双路召回 + RRF 融合（推荐）
    retrieval_mode: str = "hybrid"
    # 融合前的单路候选数。取得比最终 top_k 大，是为了让融合/重排有得挑。
    retrieval_candidate_pool: int = 20
    # BM25 参数：k1 控制词频饱和速度，b 控制文档长度惩罚强度（0=不惩罚，1=完全归一化）
    bm25_k1: float = 1.5
    bm25_b: float = 0.75
    # 关键词检索需要把整个知识库的 chunk 缓存在内存里建索引，这里控制缓存有效期
    corpus_cache_ttl_seconds: int = 300

    # ---- Agent ----
    # 工具调用最多几轮。一轮 = 模型给出若干 tool_calls，执行完再请模型作答。
    # 调大不会更聪明，只会更慢更贵：正常问题 1~2 轮就收敛，第 3 轮基本是在原地打转。
    agent_max_rounds: int = 3
    # list_documents 给每篇文档附几个内容片段作预览，供模型判断「哪篇讲这个主题」
    agent_tool_preview_chunks: int = 3

    # ---- 切片 ----
    # 每片的目标字符上限。注意这是「打包预算」而非硬切长度：切片只在结构边界
    # （段落 / 表格行 / PDF 页 / 小节）上发生，只有单个块自身超限时才会退化为字符滑窗。
    chunk_size: int = 800
    # 仅当单个块自身超过 chunk_size 时才启用的滑窗重叠长度
    chunk_overlap: int = 120
    # 重排开关。配了 rerank_api_key + rerank_base_url 时优先走远端 Cross-Encoder，
    # 否则退回本地「标识符加法提权」——它只提权、不重排序，因此不会让融合结果变差。
    rerank_enabled: bool = True
    rerank_base_url: str = ""
    rerank_api_key: str = ""
    rerank_model: str = "bge-reranker-v2-m3"

    # ---- 查询改写（Multi-Query）----
    # 是否在检索前先用模型把问题改写成几种不同说法，各自召回后再 RRF 融合。
    # 它只作用于「单次问答」路径（answering），Agent 的工具检索显式关掉它 ——
    # 理由见 app/query_rewrite.py 的模块注释与 agent_tools.knowledge_search。
    #
    # ⚠️ 默认**关闭**，这是实测后的结论，不是「还没做」。详见
    # docs/检索效果评估报告.md 第十二节，一句话版本：
    #   · 在「口语化提问」评估集上连跑两次，**只有 Recall@5 稳定提升**（91.7% → 100%）；
    #   · 排序指标两次差异很大（Hit@1 58.3% / 33.3%，而 hybrid 恒为 41.7%），
    #     说明 RRF 的融合名次对「改写质量」敏感，一次运行的数字不足以支撑默认开启；
    #   · 代价是每次提问多一次模型调用（延迟 + token）。
    # 想开启：设 AI_SERVICE_QUERY_REWRITE_ENABLED=true。
    # 要把它变成默认值，需要更大的评估集 + 多次运行取平均，而不是在 12 道题上调参。
    query_rewrite_enabled: bool = False
    # 除原问题外再生成几条改写。3 条是「召回覆盖面」与「延迟 / token 成本」的折中：
    # 每多一条就多一整套双路召回，收益是递减的。
    query_rewrite_count: int = 3
    # 改写是增强步骤，超时就退回单查询。给它比作答（60s）更短的预算，
    # 否则一个卡住的改写会把整个问答拖到用户以为服务挂了。
    query_rewrite_timeout_seconds: int = 15

    model_config = SettingsConfigDict(env_prefix="AI_SERVICE_", extra="ignore")


settings = Settings()
