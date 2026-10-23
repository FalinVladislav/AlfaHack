export type Money = number | string
export type DecisionMode = 'APPROVE' | 'COUNTEROFFER' | 'REJECT'
export type ApplicationStatus =
  | 'SUBMITTED'
  | 'CHECKING'
  | 'SCORING_ERROR'
  | 'NEEDS_INFO'
  | 'AWAITING_DECISION'
  | 'IN_REVIEW'
  | 'APPROVED'
  | 'COUNTEROFFER'
  | 'REJECTED'
  | 'OFFER_ACCEPTED'
  | 'WITHDRAWN'
  | 'CLOSED'
  | 'EXPIRED'
  | 'SIGNED'
  | 'DISBURSED'
export interface StaffUser {
  account_key: string
  role: 'STAFF'
  label?: string
}
export type RunAction = <T>(action: () => Promise<T>) => Promise<T | null>
export interface StaffApplicationsProps {
  id: string
  me: StaffUser
  revision: number
  busy: boolean
  onNavigate: (path: string) => void
  run: RunAction
}
export interface FinancingRequest {
  amount?: Money | null
  term_months?: number | null
  duration_days?: number | null
  product_id?: string
  purpose?: string
  goal_text?: string | null
  comment?: string | null
  needed_by?: string | null
  monthly_payment?: Money | null
  annual_rate?: Money | null
  total_repay?: Money | null
}
export interface QueueRow extends FinancingRequest {
  id: string
  status: ApplicationStatus
  created_at: string
  company_name?: string | null
  requested_amount?: Money | null
  requested_term_months?: number | null
  requested_duration_days?: number | null
  risk_category?: string | null
  recommendation?: string | null
  assigned_to?: string | null
}
export interface QueuePage {
  items: QueueRow[]
  total: number
  counts: Record<string, number>
  statuses: string[]
  assignees: string[]
}
export interface ScoreMetric {
  value: number | string | null
  points: number
  weight: number
}
export interface ScoringReport {
  adjusted_score?: number | null
  outcome?: string
  explanation?: string
  metrics?: Record<string, ScoreMetric>
  sources?: { credit_history?: { history?: string | null } }
}
export interface Company {
  name?: string | null
  company_id?: string | null
  okved?: string | null
  industry?: string | null
  business_age?: number | null
  annual_revenue?: Money | null
  current_assets?: Money | null
  current_liabilities?: Money | null
  risk_source?: string | null
  risk_updated_at?: string | null
  credit_history?: string | null
  overdue_days?: number | null
}
export interface BankDecision {
  decision_type: DecisionMode
  staff_key: string
  decided_at: string
  reason?: string | null
  internal_reason?: string | null
  divergence_reason?: string | null
  client_message?: string | null
}
export interface ApplicationEvent {
  created_at: string
  event_type?: string
  new_status?: string
  actor?: string | null
  details?: string | null
}
export interface ApplicationDetail {
  id: string
  version: number
  status: ApplicationStatus
  created_at: string
  assigned_to?: string | null
  next_action: string
  risk_category?: string | null
  scored_at?: string | null
  request?: FinancingRequest
  company?: Company
  decision_report?: ScoringReport | null
  obligations?: {
    id: string
    name: string
    remaining_debt?: Money | null
    monthly_payment?: Money | null
  }[]
  scoring_history?: { assessed_at: string; report?: ScoringReport | null }[]
  events?: ApplicationEvent[]
  bank_decision?: BankDecision | null
  client_response?: { response?: string | null } | null
  offer?: FinancingRequest & { id: string; version: number; expires_at?: string | null }
  contract?: { id?: string; number?: string; signed_at?: string | null }
  loan?: {
    id: string
    amount?: Money | null
    balance?: Money | null
    next_payment_date?: string | null
  } | null
  schedule?: { installment: number; due_date: string; payment: Money }[]
}
export interface DecisionPreview extends FinancingRequest {
  calculation_token: string
  amount: Money
  report?: ScoringReport | null
}
export interface DecisionBody {
  decision_type: DecisionMode
  expected_version: number
  calculation_token: string
  amount: Money
  term_months?: number | null
  duration_days?: number | null
  internal_reason: string
  client_message: string
  reason: string
  divergence_reason: string
  expires_at: string
}
export interface StaffQueueProps {
  revision: number
  me: StaffUser
  onNavigate: (path: string) => void
}
export interface StaffCardProps {
  detail: ApplicationDetail
  me: StaffUser
  busy: boolean
  onClaim: () => Promise<void>
  onRetry: () => Promise<void>
  onMode: (mode: DecisionMode) => void
}
export interface DecisionDialogProps {
  detail: ApplicationDetail
  mode: DecisionMode
  busy: boolean
  onClose: () => void
  onSave: (body: DecisionBody) => Promise<ApplicationDetail | null>
}
