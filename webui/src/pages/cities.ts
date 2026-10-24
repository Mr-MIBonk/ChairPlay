/**
 * The GPS car's start presets: a busy road in each city, several lanes wide, facing along it, with a junction or a
 * roundabout ahead to try maneuvers and lane changes on. Each point was checked against OpenStreetMap: it lies on the
 * road named, and the heading follows the road's direction of travel there.
 */
export interface City {
  /** The city's code, the preset's button. */
  code: string
  name: string
  /** The road, and which way the car faces on it. */
  road: string
  latitude: string
  longitude: string
  heading: number
}

export const CITIES: readonly City[] = [
  {
    code: 'PAR',
    name: 'Paris',
    road: 'Avenue de la Grande Armée, toward the Arc de Triomphe',
    latitude: '48.87604',
    longitude: '2.28792',
    heading: 116,
  },
  {
    code: 'LON',
    name: 'London',
    road: 'Park Lane, northbound',
    latitude: '51.50398',
    longitude: '-0.15133',
    heading: 316,
  },
  {
    code: 'BER',
    name: 'Berlin',
    road: 'Straße des 17. Juni, west to the Großer Stern',
    latitude: '52.51609',
    longitude: '13.37400',
    heading: 264,
  },
  {
    code: 'MAD',
    name: 'Madrid',
    road: 'Paseo de la Castellana, northbound',
    latitude: '40.44920',
    longitude: '-3.69043',
    heading: 4,
  },
  {
    code: 'ROM',
    name: 'Rome',
    road: 'Via Cristoforo Colombo, toward the center',
    latitude: '41.81644',
    longitude: '12.46204',
    heading: 22,
  },
  {
    code: 'VIE',
    name: 'Vienna',
    road: 'Opernring, along the Ring',
    latitude: '48.20278',
    longitude: '16.36767',
    heading: 289,
  },
  {
    code: 'NYC',
    name: 'New York',
    road: 'West Side Highway, northbound',
    latitude: '40.74389',
    longitude: '-74.00841',
    heading: 7,
  },
  {
    code: 'CHI',
    name: 'Chicago',
    road: 'Lake Shore Drive, northbound',
    latitude: '41.86750',
    longitude: '-87.61850',
    heading: 0,
  },
  {
    code: 'LAX',
    name: 'Los Angeles',
    road: 'Santa Monica Freeway, eastbound to the East LA Interchange',
    latitude: '34.02899',
    longitude: '-118.22664',
    heading: 76,
  },
  {
    code: 'MEX',
    name: 'Mexico City',
    road: 'Paseo de la Reforma, toward the Ángel',
    latitude: '19.42450',
    longitude: '-99.17200',
    heading: 55,
  },
]

const NUMBER = String.raw`(-?\d{1,3}(?:\.\d+)?)`
const PAIR = new RegExp(String.raw`^\s*${NUMBER}\s*[,\s]\s*${NUMBER}\s*$`)
// Google Maps' @lat,lon and q=lat,lon; Apple Maps' ll= and coordinate=, as the app reads gps.start.
const IN_LINK = new RegExp(String.raw`(?:@|[?&](?:q|ll|coordinate)=)${NUMBER},${NUMBER}`)

/** The latitude and longitude [text] names, as written: a pair, or the link a map app shares; null for none. */
export function parseCoordinates(text: string): { latitude: string; longitude: string } | null {
  const match = IN_LINK.exec(text) ?? PAIR.exec(text)
  if (!match) return null
  const [latitude, longitude] = [match[1], match[2]]
  if (Math.abs(Number(latitude)) > 90 || Math.abs(Number(longitude)) > 180) return null
  return { latitude, longitude }
}

/** The preset [start] stands at, if any. */
export function cityAt(start: string): City | undefined {
  const place = parseCoordinates(start)
  if (!place) return undefined
  return CITIES.find(
    (city) =>
      Math.abs(Number(city.latitude) - Number(place.latitude)) < 1e-6 &&
      Math.abs(Number(city.longitude) - Number(place.longitude)) < 1e-6,
  )
}
