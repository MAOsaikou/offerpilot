import { language, translate } from './language'

// Only these fixed, self-authored prompts are interface text. Follow-ups remain server prose.
export const assessmentQuestionKeys = [
  'First, tell me a little about yourself: what is your major, and what year are you in?',
  'What do you most often do in your free time that makes you lose track of time?',
  'Describe an experience that gave you a strong sense of achievement. What exactly did you do?',
  'Which activities do you enjoy most: building things, investigating how things work, creating and expressing yourself, helping others, organizing projects, or keeping things in order? Give an example if you can.',
  'What role do you usually play in a team? How would classmates or friends describe you?',
  'What matters most to you when choosing a job? For example: growth, income, stability, interest, impact, or work-life balance.',
  'Finally, what are your practical preferences: preferred city, expected monthly salary, and how much overtime you can accept? It is fine if you have not decided yet.',
] as const

export const questionnaireQuestionKeys: Readonly<Record<string, string>> = {
  R1: 'Repair a wobbly bookshelf so it is sturdy and usable',
  R2: 'Assemble a small device by following a diagram',
  R3: 'Observe and care for plants outdoors',
  R4: 'Use tools to turn materials into a physical object',
  R5: 'Find out why a household device is not working',
  I1: 'Design a small experiment to test an idea',
  I2: 'Compare several sets of data to find patterns',
  I3: 'Investigate the causes behind a phenomenon',
  I4: 'Reason through a difficult problem with no ready-made answer',
  I5: 'Read about a new technology to understand how it works',
  A1: 'Draw different scenes for a story',
  A2: 'Express an emotion through sound or melody',
  A3: 'Redesign an everyday object to make it more interesting',
  A4: 'Write a short piece in your own personal style',
  A5: 'Edit a video that communicates your ideas',
  S1: 'Patiently teach someone a new skill',
  S2: 'Listen to a friend and help them work through a concern',
  S3: 'Organize an activity to help people in the community',
  S4: 'Help new students learn how to settle into campus life',
  S5: 'Explain complex ideas so others can understand them',
  E1: 'Persuade teammates to support a new proposal',
  E2: 'Lead a group in turning an activity idea into reality',
  E3: 'Introduce a product to potential users',
  E4: 'Bring different opinions together to agree on a shared plan',
  E5: 'Look for an opportunity to start a small project',
  C1: 'File disorganized materials according to a set of rules',
  C2: 'Check a spreadsheet for missing information and errors',
  C3: 'Create clear steps to keep work organized',
  C4: 'Keep and organize records of income and expenses for an activity',
  C5: 'Check a batch of work against agreed standards',
}

export function assessmentQuestionText(index: number, original: string): string {
  const key = assessmentQuestionKeys[index]
  return language.value === 'en-US' && key ? translate(key) : original
}

export function questionnaireQuestionText(id: string, original: string): string {
  const key = Object.hasOwn(questionnaireQuestionKeys, id)
    ? questionnaireQuestionKeys[id]
    : undefined
  return language.value === 'en-US' && key ? translate(key) : original
}
