import { useState } from 'react'
import type { ReactElement } from 'react'
import { Alert, Button, Input, List, Space, Typography } from 'antd'
import type { LocationSelection } from '../trips/types'

interface Props {
  label: string
  search: (query: string) => Promise<LocationSelection[]>
  onSelect: (location: LocationSelection) => void
  onQueryChange?: (query: string) => void
  selected?: LocationSelection | null
}

export default function AddressSearchField({ label, search, onSelect, onQueryChange, selected }: Props): ReactElement {
  const [query, setQuery] = useState(selected?.displayName ?? '')
  const [results, setResults] = useState<LocationSelection[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function runSearch(): Promise<void> {
    const normalized = query.trim().replace(/\s+/g, ' ')
    if (normalized.length < 3 || loading) return
    setLoading(true)
    setError(null)
    try {
      setResults(await search(normalized))
    } catch {
      setResults([])
      setError('Không thể tìm địa chỉ lúc này. Vui lòng thử lại.')
    } finally {
      setLoading(false)
    }
  }

  function choose(location: LocationSelection): void {
    setQuery(location.displayName)
    setResults([])
    onSelect(location)
  }

  const slug = label.toLocaleLowerCase('vi-VN')
  return (
    <div>
      <Typography.Text strong>{label}</Typography.Text>
      <Space.Compact block style={{ marginTop: 6 }}>
        <Input aria-label={label} value={query} placeholder={`Nhập ${slug}`}
          onChange={(event) => {
            setQuery(event.target.value)
            setResults([])
            setError(null)
            onQueryChange?.(event.target.value)
          }}
          onPressEnter={() => void runSearch()} />
        <Button aria-label={`Tìm ${slug}`} onClick={() => void runSearch()} loading={loading}
          disabled={query.trim().length < 3}>Tìm</Button>
      </Space.Compact>
      {error && <Alert style={{ marginTop: 8 }} type="warning" message={error} />}
      {!loading && !error && results.length === 0 && query.trim().length >= 3 && !selected && (
        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 6 }}>
          Bấm Tìm hoặc Enter, sau đó chọn một địa chỉ chính xác.
        </Typography.Text>
      )}
      {results.length > 0 && <List bordered size="small" style={{ marginTop: 8 }} dataSource={results}
        renderItem={(item) => <List.Item>
          <Button type="link" style={{ whiteSpace: 'normal', height: 'auto', textAlign: 'left', padding: 0 }}
            onClick={() => choose(item)}>{item.displayName}</Button>
        </List.Item>} />}
    </div>
  )
}
