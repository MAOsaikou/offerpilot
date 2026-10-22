import { apiRequest } from './client'
export interface Notification {
  id: string
  kind: 'DDL' | 'NEW_JOB' | 'INTERVIEW_PLAN' | 'CAREER_TARGET' | 'PROFILE_UPDATE'
  title: string
  body: string
  link: string
  createdAt: string
  readAt: string | null
}
export interface NotificationPage {
  items: Notification[]
  unread: number
  total: number
  page: number
  size: number
}
export const getNotifications = (page = 1) =>
  apiRequest<NotificationPage>(`/notifications?page=${page}&size=20`)
export const getUnreadCount = () => apiRequest<number>('/notifications/unread-count')
export const readNotification = (id: string) =>
  apiRequest<void>(`/notifications/${encodeURIComponent(id)}/read`, { method: 'PUT' })
export const readAllNotifications = () =>
  apiRequest<void>('/notifications/read-all', { method: 'PUT' })
