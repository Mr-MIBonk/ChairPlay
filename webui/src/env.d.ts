// Vite bundles imported stylesheets; TypeScript only needs to know they exist.
declare module '*.css' {}

// An imported image is its address in the built page.
declare module '*.png' {
  const url: string
  export default url
}
