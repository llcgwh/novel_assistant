<template>
  <svg class="story-orbit" viewBox="0 0 480 350" fill="none" aria-hidden="true">
    <defs>
      <radialGradient id="orbit-glow">
        <stop stop-color="var(--accent)" stop-opacity=".16" />
        <stop offset="1" stop-color="var(--accent)" stop-opacity="0" />
      </radialGradient>
      <linearGradient
        id="page-glow"
        x1="167"
        y1="80"
        x2="300"
        y2="270"
        gradientUnits="userSpaceOnUse"
      >
        <stop stop-color="var(--accent)" />
        <stop offset="1" stop-color="var(--accent)" stop-opacity=".05" />
      </linearGradient>
    </defs>
    <ellipse cx="248" cy="183" rx="205" ry="162" fill="url(#orbit-glow)" />
    <g stroke="var(--accent)" stroke-opacity=".15">
      <ellipse
        cx="248"
        cy="183"
        rx="193"
        ry="78"
        transform="rotate(-26 248 183)"
      />
      <ellipse
        cx="248"
        cy="183"
        rx="157"
        ry="113"
        transform="rotate(32 248 183)"
      />
      <circle cx="248" cy="183" r="136" stroke-dasharray="2 9" />
      <path d="M49 183h399M248 16v327" stroke-dasharray="2 12" />
    </g>
    <g class="orbit-pages">
      <path
        d="M180 95l90 24 46 128-91-23-45-129Z"
        fill="var(--panel)"
        stroke="url(#page-glow)"
      />
      <path
        d="M167 107l90 24 46 128-91-23-45-129Z"
        fill="var(--panel)"
        stroke="url(#page-glow)"
      />
      <path
        d="M154 119l90 24 46 128-91-23-45-129Z"
        fill="var(--panel)"
        stroke="url(#page-glow)"
        stroke-width="1.5"
      />
      <path
        d="m179 157 51 14m-46 1 40 11m-34 8 50 14m-45 2 31 8"
        stroke="var(--accent)"
        stroke-opacity=".5"
      />
      <path
        d="m248 73 4-13 4 13 13 4-13 4-4 13-4-13-13-4 13-4Z"
        fill="var(--accent)"
      />
    </g>
    <g fill="var(--accent)">
      <circle cx="78" cy="213" r="4" />
      <circle cx="377" cy="105" r="3" />
      <circle cx="365" cy="278" r="4" />
      <circle cx="139" cy="74" r="2" />
    </g>
    <g
      fill="var(--muted)"
      font-size="10"
      font-family="monospace"
      letter-spacing="2"
    >
      <text x="27" y="243">CHARACTER</text>
      <text x="342" y="83">WORLD</text>
      <text x="337" y="308">STORY</text>
      <text x="199" y="329">∞ POSSIBILITIES</text>
    </g>
  </svg>
</template>
