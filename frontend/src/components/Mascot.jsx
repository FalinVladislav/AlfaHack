import React, { useEffect, useRef, useState } from 'react'

const posters = {
  guide: '/mascots/guide-transparent.webp',
  consultant: '/mascots/consultant-transparent.webp',
  manager: '/mascots/manager-transparent.webp',
}

export default function Mascot({
  kind = 'guide',
  staticImage = false,
  className = '',
  label = 'Маскот — помощник по финансированию',
}) {
  const videoRef = useRef(null)
  const hostRef = useRef(null)
  const [failed, setFailed] = useState(false)
  const imageSource =
    staticImage && kind === 'consultant' ? '/mascots/kons-static.png' : posters[kind]

  useEffect(() => {
    if (staticImage) return
    const video = videoRef.current
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
    let visible = false
    let disposed = false
    setFailed(false)

    function updatePlayback() {
      if (visible && !document.hidden && !reducedMotion.matches && !video.error) {
        video.play().catch((error) => {
          if (!disposed && error.name === 'NotSupportedError') setFailed(true)
        })
      } else {
        video.pause()
      }
    }

    const observer = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting
      updatePlayback()
    })
    observer.observe(hostRef.current)
    document.addEventListener('visibilitychange', updatePlayback)
    reducedMotion.addEventListener('change', updatePlayback)

    return () => {
      disposed = true
      observer.disconnect()
      document.removeEventListener('visibilitychange', updatePlayback)
      reducedMotion.removeEventListener('change', updatePlayback)
      video.pause()
    }
  }, [kind, staticImage])

  return (
    <div
      ref={hostRef}
      className={`mascot mascot-${kind} ${className}`}
      role="img"
      aria-label={label}
    >
      {(staticImage || failed) && <img className="mascot-poster" src={imageSource} alt="" />}
      {!staticImage && (
        <video
          ref={videoRef}
          src={`/mascots/${kind}-transparent.webm`}
          poster={posters[kind]}
          hidden={failed}
          muted
          loop
          playsInline
          preload="auto"
          aria-hidden="true"
          onError={() => setFailed(true)}
        />
      )}
    </div>
  )
}
