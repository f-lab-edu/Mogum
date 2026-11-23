import { isValidElement, useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import Markdown from 'react-markdown'
import type { Components } from 'react-markdown'
import rehypeHighlight from 'rehype-highlight'
import rehypeKatex from 'rehype-katex'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import 'katex/dist/katex.min.css'

const scrollClass = 'min-w-0 max-w-full overflow-x-auto rounded-control'
// 브라우저의 코드 로딩만 공유한다. 여러 블록이 동시에 처음 나타나도 import는 한 번이다.
let mermaidModule: Promise<typeof import('mermaid')> | undefined
let diagramSequence = 0

function MermaidBlock({ source }: { source: string }) {
  const host = useRef<HTMLDivElement>(null)

  useEffect(() => {
    // 효과마다 별도 컨테이너·id를 쓴다. 늦게 끝난 이전 렌더는 새 본문을 건드리지 않는다.
    const container = document.createElement('div')
    host.current?.append(container)
    let cancelled = false

    async function draw() {
      try {
        const { default: mermaid } = await (mermaidModule ??= import('mermaid'))
        if (cancelled) return
        mermaid.initialize({
          securityLevel: 'strict',
          startOnLoad: false,
          suppressErrorRendering: true,
        })
        const { svg } = await mermaid.render(`material-diagram-${++diagramSequence}`, source, container)
        // 원본 HTML은 전달하지 않고 strict로 생성된 SVG만 삽입한다.
        if (!cancelled) {
          container.innerHTML = svg
          const diagram = container.querySelector('svg')
          const width = Number(diagram?.getAttribute('viewBox')?.trim().split(/[\s,]+/)[2])
          if (diagram && Number.isFinite(width) && width > 0) {
            // Mermaid의 내용 너비를 유지해 작은 화면에서도 축소 대신 내부 스크롤한다.
            diagram.style.width = `${width}px`
            diagram.style.maxWidth = 'none'
            diagram.style.height = 'auto'
          }
        }
      } catch (error) {
        if (!cancelled) {
          container.className = 'whitespace-pre-wrap text-small text-error'
          container.textContent = error instanceof Error ? error.message : String(error)
        }
      }
    }

    void draw()
    return () => {
      cancelled = true
      container.remove()
    }
  }, [source])

  return <div ref={host} role="region" aria-label="다이어그램" className={`material-diagram ${scrollClass}`} />
}

const components: Components = {
  pre({ children }) {
    if (isValidElement<{ className?: string; children?: ReactNode }>(children)
      && children.props.className?.split(' ').includes('language-mermaid')) {
      return <MermaidBlock source={String(children.props.children).replace(/\n$/, '')} />
    }
    return <pre role="region" aria-label="코드 블록" className={`${scrollClass} bg-surface-soft p-4 text-small`}>{children}</pre>
  },
  table: ({ children }) => <div role="region" aria-label="표" className={scrollClass}><table>{children}</table></div>,
  a: ({ href, children }) => href
    ? <a href={href} target="_blank" rel="noopener noreferrer">{children}</a>
    : <span>{children}</span>,
  img: ({ alt }) => <>{alt}</>,
}

export default function MaterialBody({ body }: { body: string }) {
  return <div className="material-body min-w-0 max-w-full space-y-6 text-body text-text">
    <Markdown
      skipHtml
      remarkPlugins={[remarkGfm, remarkMath]}
      rehypePlugins={[
        [rehypeKatex, { trust: false }],
        [rehypeHighlight, { detect: false, plainText: ['mermaid'] }],
      ]}
      components={components}
    >{body}</Markdown>
  </div>
}
