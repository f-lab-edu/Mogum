import { StrictMode } from 'react'
import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import MaterialBody from './MaterialBody'

const { loaded, initialize, renderDiagram } = vi.hoisted(() => ({
  loaded: vi.fn(), initialize: vi.fn(), renderDiagram: vi.fn(),
}))

// 정적 import는 하지 않는다. 이 팩토리는 컴포넌트가 동적 import할 때만 실행된다.
vi.mock('mermaid', () => {
  loaded()
  return { default: { initialize, render: renderDiagram } }
})

beforeEach(() => {
  vi.clearAllMocks()
  renderDiagram.mockReset().mockImplementation(async (id: string) => ({ svg: `<svg id="${id}" viewBox="0 0 1200 300" />` }))
})

afterEach(() => vi.unstubAllGlobals())

test('제목·목록·강조·인용·표·인라인 코드를 그린다', () => {
  const { container } = render(<MaterialBody body={'# 제목\n\n## 소제목\n\n- 항목\n\n1. 순서\n\n**강조**와 *기울임*, `value`\n\n> 인용\n\n| 이름 | 값 |\n| --- | --- |\n| 학습 | 완료 |'} />)
  expect(screen.getByRole('heading', { level: 1, name: '제목' })).toBeVisible()
  expect(screen.getByRole('heading', { level: 2, name: '소제목' })).toBeVisible()
  expect(screen.getAllByRole('listitem')).toHaveLength(2)
  expect(container.querySelector('strong')).toHaveTextContent('강조')
  expect(container.querySelector('em')).toHaveTextContent('기울임')
  expect(container.querySelector('blockquote')).toHaveTextContent('인용')
  expect(screen.getByRole('table')).toHaveTextContent('학습완료')
  expect(container.querySelector('code')).toHaveTextContent('value')
})

test('언어를 지정한 코드만 문법 강조하고 코드 원문을 보존한다', () => {
  const { container } = render(<MaterialBody body={'```javascript\nconst value = "학습";\n```\n\n```\nconst plain = 1;\n```\n\n```unknown-language\nplain unknown\n```'} />)
  const blocks = container.querySelectorAll('pre code')
  expect(blocks[0]).toHaveClass('hljs', 'language-javascript')
  expect(blocks[0].querySelector('.hljs-keyword')).toHaveTextContent('const')
  expect(blocks[0]).toHaveTextContent('const value = "학습";')
  expect(blocks[1].querySelector('span')).toBeNull()
  expect(blocks[2]).toHaveTextContent('plain unknown')
})

test('인라인·블록 수식을 KaTeX로 그린다', () => {
  const { container } = render(<MaterialBody body={'수식 $x^2$\n\n$$\n\\frac{1}{2}\n$$'} />)
  expect(container.querySelectorAll('.katex')).toHaveLength(2)
  expect(container.querySelector('.katex-display .katex')).toBeInTheDocument()
})

test('깨진 수식은 KaTeX 오류로 남고 나머지 글을 그린다', () => {
  const { container } = render(<MaterialBody body={'$\\frac{1}{$\n\n남은 본문'} />)
  expect(container.querySelector('.katex-error')).toHaveTextContent('\\frac{1}{')
  expect(screen.getByText('남은 본문')).toBeVisible()
})

test('KaTeX의 신뢰가 필요한 링크·이미지 명령을 허용하지 않는다', () => {
  const { container } = render(<MaterialBody body={'$\\href{javascript:alert(1)}{위험}$\n\n$\\includegraphics{https://example.com/image.png}$'} />)
  expect(container.querySelector('.katex')).toBeInTheDocument()
  expect(container.querySelector('a, img, script')).toBeNull()
})

test('Mermaid 블록이 없으면 모듈을 불러오지 않는다', async () => {
  render(<MaterialBody body={'본문\n\n```javascript\nconst value = 1\n```'} />)
  await act(async () => { await Promise.resolve() })
  expect(loaded).not.toHaveBeenCalled()
  expect(initialize).not.toHaveBeenCalled()
})

test('Mermaid를 필요할 때 불러 strict·수동 실행으로 그린다', async () => {
  const source = 'graph TD; A-->B'
  const { container } = render(<MaterialBody body={`앞 글\n\n\`\`\`mermaid\n${source}\n\`\`\`\n\n뒤 글`} />)
  await waitFor(() => {
    expect(loaded).toHaveBeenCalledTimes(1)
    expect(initialize).toHaveBeenCalledWith(expect.objectContaining({ securityLevel: 'strict', startOnLoad: false }))
    expect(renderDiagram).toHaveBeenCalledWith(expect.any(String), source, expect.any(HTMLElement))
    expect(container.querySelector('svg')).toBeInTheDocument()
    expect(container.querySelector('svg')).toHaveStyle({ width: '1200px', maxWidth: 'none' })
  })
  expect(screen.getByText('뒤 글')).toBeVisible()
})

test('같은 화면의 여러 Mermaid 블록과 컴포넌트에 서로 다른 id를 쓴다', async () => {
  const { container } = render(<><MaterialBody body={'```mermaid\ngraph TD; A-->B\n```\n\n```mermaid\ngraph TD; B-->C\n```'} /><MaterialBody body={'```mermaid\ngraph TD; C-->D\n```'} /></>)
  await waitFor(() => {
    expect(container.querySelectorAll('svg')).toHaveLength(3)
    expect(new Set(renderDiagram.mock.calls.map(([id]) => id)).size).toBe(3)
  })
})

test('randomUUID가 없는 환경에서도 두 그림을 서로 다른 id로 그린다', async () => {
  vi.stubGlobal('crypto', Object.create(crypto, { randomUUID: { value: undefined } }))
  expect(crypto.randomUUID).toBeUndefined()
  const { container } = render(<MaterialBody body={'```mermaid\ngraph TD; A-->B\n```\n\n```mermaid\ngraph TD; B-->C\n```'} />)
  await waitFor(() => {
    expect(renderDiagram).toHaveBeenCalledTimes(2)
    expect(container.querySelectorAll('svg')).toHaveLength(2)
    expect(new Set(renderDiagram.mock.calls.map(([id]) => id)).size).toBe(2)
  })
})

test('같은 본문을 다시 마운트해도 그림 id를 재사용하지 않는다', async () => {
  const body = '```mermaid\ngraph TD; A-->B\n```'
  const first = render(<MaterialBody body={body} />)
  await waitFor(() => expect(first.container.querySelector('svg')).toBeInTheDocument())
  first.unmount()
  const second = render(<MaterialBody body={body} />)
  await waitFor(() => {
    expect(second.container.querySelector('svg')).toBeInTheDocument()
    expect(renderDiagram).toHaveBeenCalledTimes(2)
    expect(renderDiagram.mock.calls[0][0]).not.toBe(renderDiagram.mock.calls[1][0])
  })
})

test('Mermaid 실패 메시지를 글로 표시하고 다른 블록·본문을 남긴다', async () => {
  renderDiagram.mockRejectedValueOnce(new Error('Parse error: <img src=x onerror=alert(1)>'))
  const { container } = render(<MaterialBody body={'```mermaid\nbroken\n```\n\n남은 본문\n\n```mermaid\ngraph TD; A-->B\n```'} />)
  await waitFor(() => {
    expect(screen.getByText('Parse error: <img src=x onerror=alert(1)>')).toBeVisible()
    expect(container.querySelector('svg')).toBeInTheDocument()
    expect(renderDiagram).toHaveBeenCalledTimes(2)
  })
  expect(screen.getByText('남은 본문')).toBeVisible()
  expect(container.querySelector('img')).toBeNull()
})

test('늦게 끝난 이전 그림이 바뀐 본문을 덮어쓰지 않는다', async () => {
  let finish!: (value: { svg: string }) => void
  renderDiagram.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const { container, rerender } = render(<MaterialBody body={'```mermaid\ngraph TD; Old-->B\n```'} />)
  await waitFor(() => expect(renderDiagram).toHaveBeenCalledTimes(1))
  rerender(<MaterialBody body={'```mermaid\ngraph TD; New-->B\n```'} />)
  await waitFor(() => expect(container.querySelector('svg')).toBeInTheDocument())
  await act(async () => { finish({ svg: '<svg data-old="true" />' }) })
  await waitFor(() => expect(container.querySelector('[data-old]')).toBeNull())
})

test('같은 본문의 부모가 다시 렌더되어도 실패한 그림을 다시 시도하지 않는다', async () => {
  renderDiagram.mockRejectedValue(new Error('Parse error: broken'))
  const body = '```mermaid\nbroken\n```'
  const { rerender } = render(<MaterialBody body={body} />)
  await waitFor(() => expect(screen.getByText('Parse error: broken')).toBeVisible())
  rerender(<MaterialBody body={body} />)
  await waitFor(() => {
    expect(screen.getByText('Parse error: broken')).toBeVisible()
    expect(renderDiagram).toHaveBeenCalledTimes(1)
  })
})

test('StrictMode의 효과 재실행도 그림 id를 재사용하지 않는다', async () => {
  const { container } = render(<StrictMode><MaterialBody body={'```mermaid\ngraph TD; A-->B\n```'} /></StrictMode>)
  await waitFor(() => {
    expect(container.querySelectorAll('svg')).toHaveLength(1)
    expect(renderDiagram).toHaveBeenCalled()
    const ids = renderDiagram.mock.calls.map(([id]) => id)
    expect(new Set(ids).size).toBe(ids.length)
  })
})

test('원본 HTML·스크립트·이벤트 속성·마크다운 이미지를 그리지 않는다', () => {
  const { container } = render(<MaterialBody body={'<script>window.mogumUnsafe = true</script>\n\n<img src=x onerror="window.mogumUnsafe = true">\n\n![그림 대체 글](https://example.com/image.png)\n\n남은 본문'} />)
  expect(container.querySelector('script, img, [onerror]')).toBeNull()
  expect(screen.getByText('그림 대체 글')).toBeVisible()
  expect(screen.getByText('남은 본문')).toBeVisible()
  expect('mogumUnsafe' in window).toBe(false)
})

test('안전한 링크만 새 탭·noopener noreferrer로 열고 javascript 주소를 제거한다', () => {
  const { container } = render(<MaterialBody body={'[참고](https://example.com)\n\n[위험](javascript:alert%281%29)\n\n[인코딩](javascript&#x3a;alert%281%29)'} />)
  expect(screen.getByRole('link', { name: '참고' })).toHaveAttribute('target', '_blank')
  expect(screen.getByRole('link', { name: '참고' })).toHaveAttribute('rel', 'noopener noreferrer')
  expect(container.querySelector('a[href^="javascript:"]')).toBeNull()
  expect(screen.queryByRole('link', { name: '위험' })).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: '인코딩' })).not.toBeInTheDocument()
})

test('긴 코드·표·그림·블록 수식에 각각 내부 스크롤 영역을 둔다', async () => {
  const { container } = render(<MaterialBody body={'```text\n' + 'long'.repeat(100) + '\n```\n\n| 열 |\n| --- |\n| 긴 셀 |\n\n$$\nx^2\n$$\n\n```mermaid\ngraph TD; A-->B\n```'} />)
  expect(container.firstElementChild).toHaveClass('material-body', 'min-w-0', 'max-w-full', 'text-body')
  expect(screen.getByRole('region', { name: '코드 블록' })).toHaveClass('overflow-x-auto', 'max-w-full')
  expect(screen.getByRole('region', { name: '표' })).toHaveClass('overflow-x-auto', 'max-w-full')
  await waitFor(() => {
    expect(container.querySelector('svg')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '다이어그램' })).toHaveClass('overflow-x-auto', 'max-w-full')
  })
  // jsdom은 너비를 계산하지 않으므로 수식의 스크롤 규칙은 스타일시트도 확인한다.
  expect(container.querySelector('.katex-display')).toBeInTheDocument()
})
