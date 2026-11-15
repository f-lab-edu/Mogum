import reading from '../assets/mogumi-reading.png'

export default function Mogumi({ size = 'page' }: { size?: 'page' | 'logo' }) {
  return (
    <img
      src={reading}
      width={384}
      height={351}
      alt="보라색 머그컵 안에서 책을 읽는 모구미"
      className={`h-auto object-contain ${size === 'logo' ? 'w-10' : 'mx-auto w-32'}`}
    />
  )
}
