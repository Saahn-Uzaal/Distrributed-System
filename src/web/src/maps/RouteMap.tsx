import { useEffect } from 'react'
import type { ReactElement } from 'react'
import L from 'leaflet'
import { GeoJSON, MapContainer, Marker, Popup, TileLayer, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import type { RouteGeometry, TripPoint } from '../trips/types'

interface Props {
  pickup: TripPoint
  destination: TripPoint
  geometry: RouteGeometry
  height?: number
}

function markerIcon(label: string, color: string): L.DivIcon {
  return L.divIcon({
    className: '',
    html: `<span style="display:flex;align-items:center;justify-content:center;width:30px;height:30px;border-radius:50%;background:${color};color:white;border:3px solid white;box-shadow:0 2px 8px rgba(0,0,0,.35);font:700 13px system-ui">${label}</span>`,
    iconSize: [30, 30],
    iconAnchor: [15, 15]
  })
}

const pickupIcon = markerIcon('A', '#16a34a')
const destinationIcon = markerIcon('B', '#dc2626')

function FitRoute({ pickup, destination, geometry }: Pick<Props, 'pickup' | 'destination' | 'geometry'>): null {
  const map = useMap()
  useEffect(() => {
    let bounds = L.geoJSON(geometry).getBounds()
    if (!bounds.isValid()) {
      bounds = L.latLngBounds([pickup.latitude, pickup.longitude], [destination.latitude, destination.longitude])
    }
    bounds.extend([pickup.latitude, pickup.longitude])
    bounds.extend([destination.latitude, destination.longitude])
    map.fitBounds(bounds, {
      padding: [36, 36]
    })
  }, [map, pickup.latitude, pickup.longitude, destination.latitude, destination.longitude, geometry])
  return null
}

export default function RouteMap({ pickup, destination, geometry, height = 360 }: Props): ReactElement {
  const center: [number, number] = [pickup.latitude, pickup.longitude]
  return (
    <div style={{ height, overflow: 'hidden', borderRadius: 12 }} aria-label="Bản đồ tuyến đường">
      <MapContainer center={center} zoom={14} style={{ height: '100%', width: '100%' }} scrollWheelZoom>
        <TileLayer
          url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        />
        <Marker position={[pickup.latitude, pickup.longitude]} icon={pickupIcon}>
          <Popup><strong>Điểm đón</strong><br />{pickup.address}</Popup>
        </Marker>
        <Marker position={[destination.latitude, destination.longitude]} icon={destinationIcon}>
          <Popup><strong>Điểm đến</strong><br />{destination.address}</Popup>
        </Marker>
        <GeoJSON key={JSON.stringify(geometry.coordinates)} data={geometry} style={{ color: '#1677ff', weight: 5 }} />
        <FitRoute pickup={pickup} destination={destination} geometry={geometry} />
      </MapContainer>
    </div>
  )
}
