from uuid import uuid4

from app.schemas import ParseDocumentRequest, ParseTaskResponse


def submit_parse_task(request: ParseDocumentRequest) -> ParseTaskResponse:
    return ParseTaskResponse(
        task_id=str(uuid4()),
        object_key=request.object_key,
        status="ACCEPTED",
    )
