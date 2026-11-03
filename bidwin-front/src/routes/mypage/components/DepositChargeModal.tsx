import { useRef, useState } from 'react'
import type { ChangeEvent, FormEvent } from 'react'
import Button from '../../../components/ui/Button'
import Modal from '../../../components/ui/Modal'
import SegmentedControl from '../../../components/ui/SegmentedControl'
import TextInput from '../../../components/ui/TextInput'
import { useToast } from '../../../hooks/useToast'
import { requestDepositCharge } from '../../../lib/api/mypage'
import type { DepositAccount } from '../../../lib/api/mypage'
import { DEPOSIT_CHARGE_TEXT } from '../constants'

const NON_DIGIT_PATTERN = /\D/g
const POINT_UNIT = 1_000
const MAX_CHARGE_AMOUNT = 100_000_000
const SERVICE_UNAVAILABLE_STATUS = 503

function createIdempotencyKey() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `charge-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

/* 원 단위 입력값을 1,000 같은 천 단위 구분 표기로 보여준다. 상태에는 숫자만 남긴다. */
function formatAmountDigits(digits: string) {
  if (digits === '') return ''
  return Number(digits).toLocaleString('ko-KR')
}

function validateAmount(amount: number) {
  if (amount <= 0) return DEPOSIT_CHARGE_TEXT.amountRequired
  if (amount % POINT_UNIT !== 0) return DEPOSIT_CHARGE_TEXT.amountUnitInvalid
  if (amount > MAX_CHARGE_AMOUNT) return DEPOSIT_CHARGE_TEXT.amountTooLarge
  return ''
}

/*
 * 실제 결제 연동 전, 테스트를 위해 보증금 잔액을 바로 채워주는 모달.
 * 자주 쓰는 금액은 버튼으로 바로 고르고, 그 외 금액은 직접 입력한다.
 */
function DepositChargeModal({
  isOpen,
  onClose,
  onCharge,
}: {
  isOpen: boolean
  onClose: () => void
  onCharge: (deposit: DepositAccount) => void
}) {
  const { showToast } = useToast()
  const [amount, setAmount] = useState('')
  const [error, setError] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  /* 처리 여부를 모르는 시도(503·네트워크 실패)는 같은 키·금액으로만 다시 보낸다. */
  const pendingAttemptRef = useRef<{ key: string; amount: number } | null>(null)

  function handleClose() {
    if (isSubmitting) return
    setAmount('')
    setError('')
    onClose()
  }

  function handleAmountChange(event: ChangeEvent<HTMLInputElement>) {
    setAmount(event.target.value.replace(NON_DIGIT_PATTERN, ''))
    setError('')
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (isSubmitting) return

    const value = Number(amount)
    const validationError = validateAmount(value)
    if (validationError) {
      setError(validationError)
      return
    }

    setError('')
    setIsSubmitting(true)
    const pending = pendingAttemptRef.current
    const attempt = pending?.amount === value ? pending : { key: createIdempotencyKey(), amount: value }
    pendingAttemptRef.current = attempt
    const result = await requestDepositCharge(value, attempt.key)
    setIsSubmitting(false)
    if (!result.ok) {
      const outcomeUnknown = result.status === null || result.status === SERVICE_UNAVAILABLE_STATUS
      if (!outcomeUnknown) pendingAttemptRef.current = null
      setError(result.message)
      return
    }

    pendingAttemptRef.current = null
    onCharge(result.data)
    showToast(DEPOSIT_CHARGE_TEXT.done)
    setAmount('')
    onClose()
  }

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleClose}
      title={DEPOSIT_CHARGE_TEXT.modalTitle}
      description={DEPOSIT_CHARGE_TEXT.modalDescription}
    >
      <form onSubmit={handleSubmit} className="flex flex-col gap-base">
        <SegmentedControl
          label={DEPOSIT_CHARGE_TEXT.presetLabel}
          options={DEPOSIT_CHARGE_TEXT.presets}
          value={amount}
          onChange={(value) => {
            setAmount(value)
            setError('')
          }}
        />
        <TextInput
          label={DEPOSIT_CHARGE_TEXT.amountLabel}
          type="text"
          inputMode="numeric"
          suffix="원"
          value={formatAmountDigits(amount)}
          onChange={handleAmountChange}
          error={error}
        />
        <Button type="submit" disabled={amount.length === 0 || isSubmitting}>
          {isSubmitting ? DEPOSIT_CHARGE_TEXT.submitting : DEPOSIT_CHARGE_TEXT.submit}
        </Button>
      </form>
    </Modal>
  )
}

export default DepositChargeModal
