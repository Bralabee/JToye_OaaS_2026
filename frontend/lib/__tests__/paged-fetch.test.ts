/**
 * #485 — the shared paging primitive.
 *
 * The bug this exists to prevent is not "the loop is wrong", it is "the loop looks
 * right against a fixture more generous than the server". So every case here runs
 * against `springPage`, which CLAMPS `?size=` the way core-java's
 * `spring.data.web.pageable.max-page-size: 100` does, and every fixture is
 * genuinely longer than one page.
 */
import apiClient from "@/lib/api-client"
import { fetchAllPages } from "@/lib/paged-fetch"
import { pagedResponse, param, springPage } from "@/test-utils/spring-page"

jest.mock("@/lib/api-client")
const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>

interface Row {
  id: string
}

/** 250 rows — more than two clamped pages, so a tail exists past page 1 as well. */
const ROWS: Row[] = Array.from({ length: 250 }, (_, i) => ({ id: `row-${i + 1}` }))
const TAIL = "row-250"

const urls = () => mockedApiClient.get.mock.calls.map(([u]) => String(u))

const readAll = (size: number, maxPages = 50) =>
  fetchAllPages<Row>({
    buildUrl: (page, pageSize) => `/api/v1/rows?page=${page}&size=${pageSize}`,
    size,
    maxPages,
    label: "[test] /api/v1/rows",
  })

beforeEach(() => {
  mockedApiClient.get.mockReset()
})

describe("fetchAllPages", () => {
  it("returns the TAIL of a list longer than one page, not the first page", async () => {
    mockedApiClient.get.mockImplementation(((url: string) =>
      Promise.resolve(pagedResponse(url, ROWS))) as never)

    const { items, truncated } = await readAll(100)

    expect(items).toHaveLength(250)
    expect(items[items.length - 1].id).toBe(TAIL)
    expect(items.map((r) => r.id)).toContain(TAIL)
    expect(truncated).toBe(false)
  })

  it("follows the list past a page the SERVER clamped below the size we asked for", async () => {
    // THE #476 REGRESSION, AS A CASE. Asking for 200 gets 100 back, because the
    // server clamps. Judging "is this a short page?" against the 200 we asked for
    // makes the server's first FULL page look like the end of the list, and the
    // caller silently keeps 100 of 250 rows. Judging it against the `size` the
    // server reported is what makes this pass.
    mockedApiClient.get.mockImplementation(((url: string) =>
      Promise.resolve(pagedResponse(url, ROWS))) as never)

    const { items } = await readAll(200)

    expect(items).toHaveLength(250)
    expect(items.map((r) => r.id)).toContain(TAIL)
    // Clamped to 100/page ⇒ three requests, whatever we asked for.
    expect(urls()).toHaveLength(3)
    expect(param(urls()[0], "size")).toBe("200")
  })

  it("issues page=0,1,2 rather than one request", async () => {
    mockedApiClient.get.mockImplementation(((url: string) =>
      Promise.resolve(pagedResponse(url, ROWS))) as never)

    await readAll(100)

    expect(urls().map((u) => param(u, "page"))).toEqual(["0", "1", "2"])
  })

  it("stops after one request when the whole list fits on a page", async () => {
    mockedApiClient.get.mockImplementation(((url: string) =>
      Promise.resolve(pagedResponse(url, ROWS.slice(0, 7)))) as never)

    const { items, pagesRead } = await readAll(100)

    expect(items).toHaveLength(7)
    expect(pagesRead).toBe(1)
  })

  it("terminates after one request on a response carrying NO paging metadata", async () => {
    // A short page is the only signal available here, so this is the exit that
    // stops a metadata-free API from looping to the circuit breaker.
    mockedApiClient.get.mockImplementation((() =>
      Promise.resolve({ data: { content: ROWS.slice(0, 3) } })) as never)

    const { items, pagesRead, truncated } = await readAll(100)

    expect(items).toHaveLength(3)
    expect(pagesRead).toBe(1)
    expect(truncated).toBe(false)
  })

  it("honours `last: true` even while totalPages claims there is more", async () => {
    mockedApiClient.get.mockImplementation(((url: string) => {
      const page = Number(param(url, "page") ?? 0)
      return Promise.resolve({
        data: { ...springPage(ROWS, page, 100), last: true, totalPages: 99 },
      })
    }) as never)

    const { pagesRead } = await readAll(100)

    expect(pagesRead).toBe(1)
  })

  it("reports `truncated` and warns rather than looping forever", async () => {
    // An API that always claims another full page. Without the bound this hangs the
    // tab; with it the caller is TOLD the list is partial instead of being handed a
    // silent truncation, which is the distinction #485 is about.
    const warn = jest.spyOn(console, "warn").mockImplementation(() => {})
    mockedApiClient.get.mockImplementation(((url: string) => {
      const size = Number(param(url, "size") ?? 100)
      return Promise.resolve({
        data: {
          content: Array.from({ length: size }, (_, i) => ({ id: `x-${i}` })),
          size,
          totalPages: 9999,
          totalElements: 999999,
          number: 0,
          first: true,
          last: false,
        },
      })
    }) as never)

    const { pagesRead, truncated } = await readAll(100, 4)

    expect(pagesRead).toBe(4)
    expect(truncated).toBe(true)
    expect(urls()).toHaveLength(4)
    expect(warn).toHaveBeenCalledWith(expect.stringContaining("4-page bound"))
    warn.mockRestore()
  })

  it("stops on an empty page without adding a phantom request", async () => {
    mockedApiClient.get.mockImplementation((() =>
      Promise.resolve({ data: { content: [], totalPages: 5, size: 100 } })) as never)

    const { items, pagesRead } = await readAll(100)

    expect(items).toHaveLength(0)
    expect(pagesRead).toBe(1)
  })
})

describe("the fixture itself", () => {
  // A fixture is an instrument. These two cases are the positive control: if
  // `springPage` stopped clamping, or stopped reproducing PageImpl's total rule,
  // every case above would still pass while testing something weaker than reality.
  it("clamps ?size= to the server maximum and reports the clamped size", () => {
    const page = springPage(ROWS, 0, 200)

    expect(page.content).toHaveLength(100)
    expect(page.size).toBe(100)
    expect(page.last).toBe(false)
  })

  it("can be told to honour any size, which is how the clamp is falsifiable", () => {
    const page = springPage(ROWS, 0, 200, Infinity)

    expect(page.content).toHaveLength(200)
    expect(page.size).toBe(200)
  })
})
