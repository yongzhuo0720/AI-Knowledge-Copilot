"""检索判分的公共口径。

判分逻辑决定所有指标是否可信，所以它必须只有一份。评估脚本
(:mod:`scripts.evaluate_retrieval`) 与索引自检脚本 (``scripts/verify_index.py``)
都要判断「一个片段算不算命中正确答案」，如果两边各写一份，只要有一边改了判分口径，
就会出现「评估说 MISS、自检说可答」这种自相矛盾的结果 —— 而自检的全部意义恰恰是
给评估提供独立但同口径的证据。

所以这里把三件事抽出来共用：

- :func:`span_alternatives` —— 把一道题的判分依据归一成「若干备选，每个备选是一组
  必须同时出现的字面串」；
- :func:`rank_of_first_match` —— 在候选列表里找第一个满足任一备选的片段，返回名次；
- :func:`summarize` —— 把逐题名次汇总成 Recall / MRR / Hit@1。

本模块只做纯字符串处理，不依赖 Milvus、不依赖检索器，因此可以被直接单测。
"""


def span_alternatives(item: dict) -> list[list[str]]:
    """把一道题的判分依据归一成「若干备选，每个备选是一组必须同时出现的字面串」。

    两种写法：

    - ``answer_span``：单个串或串数组。数组时全部出现才算命中 —— 用来堵住
      「只认编号」的漏洞（只罗列型号的目录片段同样含 ``2500``；错误码 ``1836``
      也同时出现在完整条目和被截断的残句里）。
    - ``answer_any``：备选数组，任一备选成立即算命中 —— 用来处理「同一问题在语料里
      有两个都正确的片段」的情况。例如「两路检索结果是怎么合并的」，
      ``retrieval-overview.md`` 写「只看名次不看分数，把两路的名次做倒数后相加」，
      ``hybrid-retrieval.md`` 写「只使用名次不使用分数」，两处都是对的。
      把判分钉在其中一篇上，会让检索器因为「挑中了另一个正确片段」而被判错，
      把题目歧义记成检索缺陷。
    """
    if item.get("answer_any"):
        alternatives: list[list[str]] = []
        for option in item["answer_any"]:
            values = option if isinstance(option, (list, tuple)) else [option]
            alternatives.append([str(value) for value in values if str(value)])
        return [option for option in alternatives if option]
    span = item.get("answer_span")
    if span is None:
        return []
    values = span if isinstance(span, (list, tuple)) else [span]
    return [[str(value) for value in values if str(value)]]


def _lower(alternatives: list[list[str]]) -> list[list[str]]:
    return [[target.lower() for target in option] for option in alternatives]


def _matches_lowered(content: str, lowered_alternatives: list[list[str]]) -> bool:
    """内部快路径：备选串需**已经**小写化。

    单独拆出来是为了在遍历大量候选时只做一次小写化，而不是每个候选都做一遍。
    公开 API 是 :func:`matches` —— 它自己负责小写化，避免调用方漏做这一步
    （这个坑很容易踩：忘了小写化时不会报错，只会静默匹配失败）。
    """
    text = content.lower()
    return any(all(target in text for target in option) for option in lowered_alternatives)


def matches(content: str, alternatives: list[list[str]]) -> bool:
    """单个片段是否满足任一备选。大小写不敏感，调用方无需预处理。"""
    if not alternatives:
        return False
    return _matches_lowered(content, _lower(alternatives))


def matched_candidates(candidates: list[dict], alternatives: list[list[str]]) -> list[dict]:
    """返回所有满足任一备选的候选。

    自检脚本需要它来回答「答案落在哪几篇文档里」。把「哪些算命中」这件事集中在这里，
    是为了避免评估与自检各写一遍匹配循环 —— 两份实现只要有一边改了口径，
    就会出现「评估说 MISS、自检说可答」这种自相矛盾的结果。
    """
    if not alternatives:
        return []
    lowered = _lower(alternatives)
    return [
        candidate
        for candidate in candidates
        if _matches_lowered(str(candidate.get("content") or ""), lowered)
    ]


def rank_of_first_match(candidates: list[dict], alternatives: list[list[str]]) -> int | None:
    """返回第一个满足任一备选的候选的名次（从 1 开始），没有则返回 None。

    ``candidates`` 是形如 ``[{"content": "..."}]`` 的列表。评估脚本传检索结果，
    自检脚本传库里的全部 chunk —— 同一套函数既可判断「检索排第几」，
    也可判断「库里到底有没有」。
    """
    if not alternatives:
        return None
    lowered = _lower(alternatives)
    for position, candidate in enumerate(candidates):
        if _matches_lowered(str(candidate.get("content") or ""), lowered):
            return position + 1
    return None


def summarize(records: list[dict]) -> dict[str, float]:
    """把逐题名次汇总成指标。

    ``records`` 是形如 ``[{"rank": 1 | 2 | ... | None}]`` 的列表。未召回的题
    （``rank is None``）按 0 计入 MRR 的分母，否则「完全没召回」和「排在第 1」
    会被算成同一件事。
    """
    total = max(len(records), 1)
    return {
        "recall": sum(1 for item in records if item["rank"] is not None) / total,
        "mrr": sum(1.0 / item["rank"] for item in records if item["rank"] is not None) / total,
        "hit1": sum(1 for item in records if item["rank"] == 1) / total,
        "total": len(records),
    }
