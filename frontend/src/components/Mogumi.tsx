import reading from '../assets/mogumi-reading.png'

export default function Mogumi() {
  return (
    <img
      src={reading}
      width={384}
      height={351}
      alt="보라색 머그컵 안에서 책을 읽는 모구미"
      className="mx-auto h-auto w-32 object-contain"
    />
  )
}
