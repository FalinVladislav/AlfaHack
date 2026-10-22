import React, { useEffect, useRef, useState } from 'react'

// Remove only the backdrop connected to the edges, keeping similarly coloured details intact.
function clearBackdrop(frame, width, height, kind, visited, queue) {
  const pixels = frame.data
  visited.fill(0)
  let head = 0
  let tail = 0
  function push(position) {
    if (visited[position]) return
    visited[position] = 1
    const index = position * 4
    const r = pixels[index]
    const g = pixels[index + 1]
    const b = pixels[index + 2]
    const backdrop =
      kind === 'guide'
        ? r > 175 &&
          g > 145 &&
          b > 190 &&
          b > g * 1.04 &&
          r > g * 1.04 &&
          b - g < 95 &&
          r - g < 65 &&
          Math.abs(r - b) < 65
        : kind === 'manager'
          ? r > 95 && g > 130 && b > 170 && b > r * 1.15 && b > g * 1.04 && b - r < 130
          : g > 135 && r > 100 && b < g * 0.88 && g > r * 0.91 && g - r < 100
    if (backdrop) {
      pixels[index + 3] = 0
      queue[tail++] = position
    }
  }
  for (let x = 0; x < width; x++) {
    push(x)
    push((height - 1) * width + x)
  }
  for (let y = 0; y < height; y++) {
    push(y * width)
    push(y * width + width - 1)
  }
  while (head < tail) {
    const position = queue[head++]
    const x = position % width
    if (x) push(position - 1)
    if (x < width - 1) push(position + 1)
    if (position >= width) push(position - width)
    if (position < width * (height - 1)) push(position + width)
  }
}

export default function Mascot({
  kind = 'guide',
  className = '',
  label = 'Маскот — помощник по финансированию',
}) {
  const videoRef = useRef(null)
  const canvasRef = useRef(null)
  const hostRef = useRef(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const video = videoRef.current
    const canvas = canvasRef.current
    const context = canvas.getContext('2d', { willReadFrequently: true })
    if (!context) return
    let animation
    let visible = true
    let stopped = false
    let previous = 0
    const width = 360
    const height = 540
    const visited = new Uint8Array(width * height)
    const queue = new Int32Array(width * height)
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
    canvas.width = width
    canvas.height = height

    function draw(time) {
      if (stopped) return
      if (visible && !document.hidden && video.readyState >= 2 && time - previous > 65) {
        previous = time
        context.drawImage(video, 0, 0, width, height)
        const frame = context.getImageData(0, 0, width, height)
        clearBackdrop(frame, width, height, kind, visited, queue)
        context.putImageData(frame, 0, 0)
        if (reducedMotion.matches) video.pause()
      }
      animation = requestAnimationFrame(draw)
    }

    const observer = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting
      if (visible && !reducedMotion.matches) video.play().catch(() => {})
      else video.pause()
    })
    observer.observe(hostRef.current)
    video.play().catch(() => {})
    animation = requestAnimationFrame(draw)
    return () => {
      stopped = true
      cancelAnimationFrame(animation)
      observer.disconnect()
      video.pause()
    }
  }, [kind])

  return (
    <div ref={hostRef} className={`mascot ${className}`} role="img" aria-label={label}>
      {failed ? (
        <div className="mascot-fallback">Ваш следующий шаг — ближе</div>
      ) : (
        <>
          <video
            ref={videoRef}
            src={`/mascots/${kind}.webm`}
            muted
            loop
            playsInline
            preload="auto"
            aria-hidden="true"
            onError={() => setFailed(true)}
          />
          <canvas ref={canvasRef} aria-hidden="true" />
        </>
      )}
    </div>
  )
}
