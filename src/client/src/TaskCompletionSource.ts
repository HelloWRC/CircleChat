export default class TaskCompletionSource<T> {
  public readonly promise: Promise<T>

  private readonly resolvePromise: (value: T | PromiseLike<T>) => void
  private readonly rejectPromise: (reason?: unknown) => void

  constructor() {
    let resolve!: (value: T | PromiseLike<T>) => void
    let reject!: (reason?: unknown) => void

    this.promise = new Promise<T>((res, rej) => {
      resolve = res
      reject = rej
    })

    this.resolvePromise = resolve
    this.rejectPromise = reject
  }

  setResult(value: T): void {
    this.resolvePromise(value)
  }

  setException(error: unknown): void {
    this.rejectPromise(error)
  }
}
