/*
 * 백엔드 공통 응답 봉투(ApiResponse<T>)를 벗겨 성공·실패를 한 가지 결과 타입으로 돌려준다.
 * 화면은 try/catch 없이 result.ok만 보고 분기하고, 실패 메시지는 그대로 오류 영역에 보여준다.
 */

/*
 * 서버 주소는 환경 변수로만 주입한다(코드에 주소를 박지 않는다).
 * 값이 없으면 같은 오리진으로 보내고, 개발 환경에서는 vite.config.ts의 /api 프록시가 백엔드로 넘긴다.
 */
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

const POST_METHOD = 'POST'
const PATCH_METHOD = 'PATCH'
const DELETE_METHOD = 'DELETE'
const CONTENT_TYPE_HEADER = 'Content-Type'
const JSON_CONTENT_TYPE = 'application/json'
/* 세션 쿠키 기반 인증이므로 쿠키를 항상 함께 보낸다. */
const CREDENTIALS_MODE = 'include'

const NETWORK_ERROR_MESSAGE = '네트워크 상태를 확인한 뒤 다시 시도해주세요.'
const FALLBACK_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해주세요.'

interface ApiEnvelope<TData> {
  success: boolean
  data: TData | null
  error: { code?: string; message?: string } | null
}

interface ApiSuccess<TData> {
  ok: true
  data: TData
}

interface ApiFailure {
  ok: false
  /* 화면에 바로 보여줄 수 있는 사용자용 메시지 */
  message: string
  /* 백엔드 ErrorCode의 코드(MEMBER_409_1 등). 응답을 읽지 못했으면 null */
  code: string | null
  status: number | null
}

type ApiResult<TData> = ApiSuccess<TData> | ApiFailure

async function readEnvelope<TData>(response: Response) {
  try {
    return (await response.json()) as ApiEnvelope<TData>
  } catch {
    /* 본문이 비어 있거나 JSON이 아닌 응답(프록시 오류 등)은 봉투 없이 처리한다. */
    return null
  }
}

async function requestEnvelope<TData>(
  path: string,
  init: RequestInit,
): Promise<ApiResult<TData>> {
  let response: Response

  try {
    response = await fetch(apiUrl(path), {
      credentials: CREDENTIALS_MODE,
      ...init,
    })
  } catch {
    return { ok: false, message: NETWORK_ERROR_MESSAGE, code: null, status: null }
  }

  const envelope = await readEnvelope<TData>(response)

  if (!response.ok || envelope === null || !envelope.success) {
    return {
      ok: false,
      message: envelope?.error?.message ?? FALLBACK_ERROR_MESSAGE,
      code: envelope?.error?.code ?? null,
      status: response.status,
    }
  }

  return { ok: true, data: envelope.data as TData }
}

function postJson<TData, TBody>(
  path: string,
  body: TBody,
  headers?: Record<string, string>,
): Promise<ApiResult<TData>> {
  return requestEnvelope<TData>(path, {
    method: POST_METHOD,
    headers: { [CONTENT_TYPE_HEADER]: JSON_CONTENT_TYPE, ...headers },
    body: JSON.stringify(body),
  })
}

function patchJson<TData, TBody>(path: string, body: TBody): Promise<ApiResult<TData>> {
  return requestEnvelope<TData>(path, {
    method: PATCH_METHOD,
    headers: { [CONTENT_TYPE_HEADER]: JSON_CONTENT_TYPE },
    body: JSON.stringify(body),
  })
}

function deleteJson<TData>(path: string): Promise<ApiResult<TData>> {
  return requestEnvelope<TData>(path, { method: DELETE_METHOD })
}

function getJson<TData>(path: string, signal?: AbortSignal): Promise<ApiResult<TData>> {
  return requestEnvelope<TData>(path, { method: 'GET', signal })
}

function apiUrl(path: string): string {
  return `${API_BASE_URL}${path}`
}

export { apiUrl, deleteJson, getJson, patchJson, postJson }
export type { ApiFailure, ApiResult, ApiSuccess }
