import { translate as uiText } from '@/shared/utils/language'
import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import './meta'
import { registerGuards } from './guards'

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    component: () => import('@/app/layouts/AuthLayout.vue'),
    meta: { public: true },
    children: [
      {
        path: '',
        name: 'login',
        component: () => import('@/features/auth/views/LoginView.vue'),
        meta: { title: uiText('Sign in'), public: true },
      },
    ],
  },
  {
    path: '/',
    component: () => import('@/app/layouts/AppLayout.vue'),
    children: [
      {
        path: '',
        name: 'dashboard',
        component: () => import('@/features/dashboard/views/DashboardView.vue'),
        meta: { title: uiText('Dashboard') },
      },
      {
        path: 'chat/:conversationId?',
        name: 'chat',
        component: () => import('@/app/views/ChatPage.vue'),
        meta: { title: uiText('Chat') },
      },
      {
        path: 'discover/:tab?',
        name: 'discover',
        component: () => import('@/features/discovery/views/DiscoverView.vue'),
        meta: { title: uiText('Career discovery') },
      },
      {
        path: 'resumes',
        name: 'resumes',
        component: () => import('@/features/resume/views/ResumeListView.vue'),
        meta: { title: uiText('Resume') },
      },
      {
        path: 'resumes/:id',
        name: 'resume-detail',
        component: () => import('@/app/views/ResumeDetailPage.vue'),
        meta: { title: uiText('Resume details') },
      },
      {
        path: 'jobs',
        name: 'jobs',
        component: () => import('@/features/job/views/JobSearchView.vue'),
        meta: { title: uiText('Jobs') },
      },
      {
        path: 'jobs/:id',
        name: 'job-detail',
        component: () => import('@/features/job/views/JobDetailView.vue'),
        meta: { title: uiText('Job details') },
      },
      {
        path: 'board',
        name: 'board',
        component: () => import('@/features/board/views/BoardView.vue'),
        meta: { title: uiText('Application board') },
      },
      {
        path: 'interviews',
        name: 'interviews',
        component: () => import('@/features/interview/views/InterviewListView.vue'),
        meta: { title: uiText('Mock interview') },
      },
      {
        path: 'interviews/:id',
        name: 'interview-room',
        component: () => import('@/features/interview/views/InterviewRoomView.vue'),
        meta: { title: uiText('Interview room') },
      },
      {
        path: 'career',
        name: 'career',
        component: () => import('@/app/views/CareerPage.vue'),
        meta: { title: uiText('Career planning') },
      },
      {
        path: 'kb/builtin/:occupationId?',
        name: 'builtin-kb',
        component: () => import('@/features/kb/views/BuiltinKnowledgeView.vue'),
        meta: { title: uiText('Built-in knowledge') },
      },
      {
        path: 'kb',
        name: 'kb',
        component: () => import('@/features/kb/views/KnowledgeView.vue'),
        meta: { title: uiText('Knowledge library') },
      },
      {
        path: 'settings/:tab?',
        name: 'settings',
        component: () => import('@/features/settings/views/SettingsView.vue'),
        meta: { title: uiText('Settings') },
      },
      {
        path: 'integrations/mcp',
        name: 'mcp',
        component: () => import('@/features/mcp/views/McpManagementView.vue'),
        meta: { title: uiText('MCP connections') },
      },
      {
        path: 'notifications',
        name: 'notifications',
        component: () => import('@/features/notifications/views/NotificationsView.vue'),
        meta: { title: uiText('Notifications') },
      },
    ],
  },
  {
    path: '/staff/login',
    name: 'staff-login',
    component: () => import('@/features/auth/views/StaffLoginView.vue'),
    meta: { title: uiText('Staff sign-in'), public: true },
  },
  {
    path: '/staff/retrieval-debug',
    name: 'retrieval-debug',
    component: () => import('@/features/kb/views/RetrievalDebugView.vue'),
    meta: { title: uiText('Retrieval diagnostics'), staff: true },
  },
  {
    path: '/staff',
    name: 'staff-console',
    component: () => import('@/features/auth/views/StaffConsoleView.vue'),
    meta: { title: uiText('Staff console'), staff: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/shared/views/NotFoundView.vue'),
    meta: { title: uiText('Page not found'), public: true },
  },
]

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior(_to, from, savedPosition) {
    // Fresh documents start at the top. A redundant scrollTo here forces layout
    // before the route and its fonts can paint; keep restoration/navigation below.
    if (!from.matched.length && !savedPosition) return false
    return savedPosition ?? { top: 0 }
  },
})

registerGuards(router)
