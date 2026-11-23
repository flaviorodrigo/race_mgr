import React from 'react';
import TableCell from '@mui/material/TableCell';
import TableSortLabel from '@mui/material/TableSortLabel';

export type Order = 'asc' | 'desc';

// Click a column to sort by it; click again to reverse. New columns start in `defaultOrder`
// (text ascending, numbers descending, which is what people expect to see first).
export const useSort = <K extends string>(initialKey: K, initialOrder: Order = 'desc') => {
	const [orderBy, setOrderBy] = React.useState<K>(initialKey);
	const [order, setOrder] = React.useState<Order>(initialOrder);

	const requestSort = (key: K, defaultOrder: Order) => {
		if (key === orderBy) {
			setOrder(order === 'asc' ? 'desc' : 'asc');
		} else {
			setOrderBy(key);
			setOrder(defaultOrder);
		}
	};

	return {orderBy, order, requestSort};
};

interface ISortHeadCellProps<K extends string> {
	sortKey: K,
	label: string,
	orderBy: K,
	order: Order,
	onSort: (key: K, defaultOrder: Order) => void,
	align?: 'left' | 'right',
	// Text columns start ascending, numeric columns descending
	text?: boolean,
	// The cell component to render (e.g. a styled TableCell)
	cell?: React.ElementType,
}

export const SortHeadCell = <K extends string>(props: ISortHeadCellProps<K>) => {
	const Cell = props.cell ?? TableCell;
	const active = props.orderBy === props.sortKey;

	return (
		<Cell align={props.align} sortDirection={active ? props.order : false}>
			<TableSortLabel
				active={active}
				direction={active ? props.order : (props.text ? 'asc' : 'desc')}
				onClick={() => props.onSort(props.sortKey, props.text ? 'asc' : 'desc')}
				// Keep the label readable on coloured header cells
				sx={{'&, &:hover, &.Mui-active': {color: 'inherit'}, '& .MuiTableSortLabel-icon': {color: 'inherit !important'}}}
			>
				{props.label}
			</TableSortLabel>
		</Cell>
	);
};
