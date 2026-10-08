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
        meta: { title: '登录', public: true },
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
        meta: { title: '工作台' },
      },
      {
        path: 'chat/:conversationId?',
        name: 'chat',
        component: () => import('@/app/views/ChatPage.vue'),
        meta: { title: '对话' },
      },
      {
        path: 'discover/:tab?',
        name: 'discover',
        component: () => import('@/features/discovery/views/DiscoverView.vue'),
        meta: { title: '职业探索' },
      },
      {
        path: 'resumes',
        name: 'resumes',
        component: () => import('@/features/resume/views/ResumeListView.vue'),
        meta: { title: '简历' },
      },
      {
        path: 'resumes/:id',
        name: 'resume-detail',
        component: () => import('@/features/resume/views/ResumeDetailView.vue'),
        meta: { title: '简历详情' },
      },
      {
        path: 'jobs',
        name: 'jobs',
        component: () => import('@/features/job/views/JobSearchView.vue'),
        meta: { title: '岗位' },
      },
      {
        path: 'jobs/:id',
        name: 'job-detail',
        component: () => import('@/features/job/views/JobDetailView.vue'),
        meta: { title: '岗位详情' },
      },
      {
        path: 'board',
        name: 'board',
        component: () => import('@/features/board/views/BoardView.vue'),
        meta: { title: '求职看板' },
      },
      {
        path: 'interviews',
        name: 'interviews',
        component: () => import('@/features/interview/views/InterviewListView.vue'),
        meta: { title: '模拟面试' },
      },
      {
        path: 'interviews/:id',
        name: 'interview-room',
        component: () => import('@/features/interview/views/InterviewRoomView.vue'),
        meta: { title: '面试室' },
      },
      {
        path: 'career',
        name: 'career',
        component: () => import('@/features/career/views/CareerView.vue'),
        meta: { title: '职业规划' },
      },
      {
        path: 'kb',
        name: 'kb',
        component: () => import('@/features/kb/views/KnowledgeView.vue'),
        meta: { title: '知识库' },
      },
      {
        path: 'settings/:tab?',
        name: 'settings',
        component: () => import('@/features/settings/views/SettingsView.vue'),
        meta: { title: '设置' },
      },
      {
        path: 'notifications',
        name: 'notifications',
        component: () => import('@/features/notifications/views/NotificationsView.vue'),
        meta: { title: '通知' },
      },
    ],
  },
  {
    path: '/staff/login',
    name: 'staff-login',
    component: () => import('@/features/auth/views/StaffLoginView.vue'),
    meta: { title: '客服登录', public: true },
  },
  {
    path: '/staff',
    name: 'staff-console',
    component: () => import('@/features/auth/views/StaffConsoleView.vue'),
    meta: { title: '客服工作台', staff: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/shared/views/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true },
  },
]

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
})

registerGuards(router)
