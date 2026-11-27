import { SelectField } from './SelectField';
import type { BookRow } from './books';

export interface BookPickerProps {
  books: BookRow[];
  value: string;
  onChange: (id: string) => void;
}

export function BookPicker({ books, value, onChange }: BookPickerProps) {
  return (
    <SelectField
      label="Book"
      value={value}
      options={books.map((b) => ({ value: b.id, label: `${b.name} · ${b.currency}${b.status === 'ACTIVE' ? '' : ` (${b.status.toLowerCase()})`}` }))}
      onChange={(e) => onChange(e.target.value)}
    />
  );
}
