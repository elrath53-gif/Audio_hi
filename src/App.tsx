import { useEffect, useRef } from 'react';
import { bootEstudioEQ } from './estudioEq';

export default function App() {
  const shellRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (shellRef.current) {
      bootEstudioEQ(shellRef.current);
    }
  }, []);

  return (
    <div ref={shellRef} className="studio-shell">
      <header className="top">
        <h1 className="brand-title">
          <img
            src="icons/icon-192.png"
            alt=""
            aria-hidden="true"
            className="brand-ico"
          />
          <span className="brand-txt">
            Estudio <span>Master Hi-Res</span>
          </span>
        </h1>
        <button
          className="sw"
          id="bypass"
          role="switch"
          aria-checked="true"
          aria-label="Procesado de audio activo"
        >
          <i />
        </button>
        <button className="btn pri" id="saveAll" type="button">
          Guardar
        </button>
        <button className="btn ghost" id="resetAll" type="button">
          Cero
        </button>
      </header>

      <canvas
        id="viz"
        aria-label="Analizador de espectro y curva de ecualización total"
        role="img"
      />

      <main id="main">
        <div className="dock" id="playerDock">
          <div className="dt">
            <span id="dTitle">Sin pista</span>
            <span id="dSpec" className="num">
              Motor C++ 64-bit · Reloj 1:1 Bit-Perfect
            </span>
            <span id="dTime" className="num">
              0:00 / 0:00
            </span>
          </div>
          <input
            type="range"
            id="seek"
            min="0"
            max="1"
            step="0.05"
            defaultValue="0"
            aria-label="Posición de la pista"
          />
          <div className="dc">
            <button className="tb" id="bPrev" type="button" aria-label="Anterior">
              <svg viewBox="0 0 24 24">
                <path d="M6 5h2v14H6zM20 5v14L9 12z" />
              </svg>
            </button>
            <button className="tb play" id="bPlay" type="button" aria-label="Reproducir">
              <svg viewBox="0 0 24 24" id="playIcon">
                <path d="M8 5v14l11-7z" />
              </svg>
            </button>
            <button className="tb" id="bNext" type="button" aria-label="Siguiente">
              <svg viewBox="0 0 24 24">
                <path d="M16 5h2v14h-2zM4 5l11 7L4 19z" />
              </svg>
            </button>
          </div>
        </div>
      </main>

      <nav className="tabs" id="tabs" role="tablist" aria-label="Secciones" />

      <div id="toast" role="status" aria-live="polite" />
    </div>
  );
}
