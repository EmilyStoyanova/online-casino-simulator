import { describe, expect, it } from 'vitest'
import { ApiError, CSRF_UNREADABLE_MESSAGE, UNAVAILABLE_MESSAGE, UNREACHABLE_MESSAGE } from '../services/apiClient'
import { apiErrorMessage, serverFieldMessage } from './serverMessages'
import { createI18n } from './translate'

const en = createI18n('en')
const bg = createI18n('bg')

describe('serverFieldMessage: the backend field messages (Problem Details errors[].message)', () => {
  it.each([
    ['must be less than or equal to 1000', 'трябва да е по-малко или равно на 1000'],
    ['must be greater than or equal to 1', 'трябва да е по-голямо или равно на 1'],
    ['size must be between 1 and 10', 'броят трябва да е между 1 и 10'],
    ['must not be null', 'е задължително'],
    ['must not contain the same selection twice', 'не трябва да съдържа един и същ залог два пъти'],
    ['must be omitted for this category', 'не трябва да се подава за тази категория'],
    ['must be at least 12 characters long', 'трябва да е поне 12 знака'],
    ['must be at most 72 bytes long in UTF-8', 'трябва да е най-много 72 байта в UTF-8'],
    ['must not be a commonly used password', 'не трябва да е често използвана парола'],
    ['must be 3 to 30 characters long', 'трябва да е от 3 до 30 знака'],
    ['must be the code of an active interest', 'трябва да е код на активен интерес'],
    ['is not a valid cursor', 'не е валиден курсор'],
    ['has an invalid value', 'има невалидна стойност'],
  ])('%j is translated, and repeated unchanged in English', (server, bulgarian) => {
    const message = serverFieldMessage(server)
    expect(en.t(message)).toBe(server)
    expect(bg.t(message)).toBe(bulgarian)
  })

  it('shows a message it does not know exactly as the server sent it', () => {
    const message = serverFieldMessage('must be a prime number')
    expect(en.t(message)).toBe('must be a prime number')
    expect(bg.t(message)).toBe('must be a prime number')
  })

  it('keeps numbers as the server wrote them (no grouping added)', () => {
    expect(en.t(serverFieldMessage('must be less than or equal to 10000'))).toBe('must be less than or equal to 10000')
  })
})

describe('apiErrorMessage: the API client texts', () => {
  it('translates the client’s own messages', () => {
    expect(bg.t(apiErrorMessage(new ApiError(UNREACHABLE_MESSAGE, 0)))).toBe(
      'Сървърът не може да бъде достигнат. Моля, проверете връзката си.',
    )
    expect(bg.t(apiErrorMessage(new ApiError(UNAVAILABLE_MESSAGE, 502)))).toMatch(/^Сървърът стартира/)
    expect(bg.t(apiErrorMessage(new ApiError(CSRF_UNREADABLE_MESSAGE, 0)))).toMatch(/^Защитният токен/)
    expect(bg.t(apiErrorMessage(new ApiError('Request failed with status 500', 500)))).toBe('Заявката е неуспешна (статус 500)')
    expect(en.t(apiErrorMessage(new ApiError(UNREACHABLE_MESSAGE, 0)))).toBe(UNREACHABLE_MESSAGE)
  })

  it('shows any other message unchanged', () => {
    expect(bg.t(apiErrorMessage(new ApiError('Something else', 0)))).toBe('Something else')
  })
})
