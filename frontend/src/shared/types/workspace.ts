/** 本地工作区模型，独立于尚未实现的服务端 DTO。 */
export type Stage = '收藏' | '已投递' | '面试中' | 'Offer' | '已结束'
export interface Job {
  id: string
  title: string
  company: string
  city: string
  salary: string
  category: string
  tags: string[]
  description: string
  color: string
}
export interface Application {
  id: string
  jobId: string
  stage: Stage
  note: string
  date: string
  jobSnapshot?: Job
}
export interface Resume {
  id: string
  title: string
  content: string
  updated: string
  versions: { content: string; date: string }[]
}
export interface Interview {
  id: string
  role: string
  kind: string
  answers: string[]
  question: number
  completed: boolean
  date: string
}
export interface Note {
  id: string
  title: string
  content: string
  category: string
}
export interface Notice {
  id: string
  text: string
  date: string
  read: boolean
}
export interface Workspace {
  version: 1
  name: string
  target: string
  city: string
  skills: string
  interests: string[]
  resumes: Resume[]
  applications: Application[]
  interviews: Interview[]
  notes: Note[]
  tasks: string[]
  notices: Notice[]
}
