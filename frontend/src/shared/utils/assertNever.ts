/** 联合类型穷尽检查：新增分支时编译期报错（规范 §13） */
export function assertNever(value: never): never {
  throw new Error(`Unexpected value: ${JSON.stringify(value)}`)
}
