import React from 'react';
import _ from 'lodash';

import {convertEpochDate} from '../../utils/epochUtils';

// The pickers work with Date | null; the rest of the app stores epoch seconds
const epochToDate = (epoch: number): Date | null => {
	const value: unknown = convertEpochDate(epoch);
	return value instanceof Date ? value : null;
};

const dateToEpoch = (date: Date | null): number => (date && !isNaN(date.valueOf()) ? Math.floor(date.getTime() / 1000) : 0);

import Box from '@mui/material/Box';
import Grid from '@mui/material/Grid';
import {DatePicker} from '@mui/x-date-pickers/DatePicker';
import {AdapterDateFns} from '@mui/x-date-pickers/AdapterDateFns';
import {LocalizationProvider} from '@mui/x-date-pickers/LocalizationProvider';

interface IProps {
	currentSessionId: string,
	startDate:number,
	setStartDate: (startDate:number) => void,
	endDate: number,
	setEndDate: (endDate:number) => void
}

const DateFilter = (props:IProps) => (
	<Box sx={{display: props.startDate > 0 ? '' : 'none'}}>
		<Grid container spacing={3} hidden={true}>
			<Grid size={{xs: 12, md: 3}}>
				<LocalizationProvider dateAdapter={AdapterDateFns}>
					<DatePicker
						label="Start Date"
						value={epochToDate(props.startDate)}
						onChange={newValue => {
							props.setStartDate(dateToEpoch(newValue));
						}}
					/>
				</LocalizationProvider>
			</Grid>
			<Grid size={{xs: 12, md: 3}}>
				<LocalizationProvider dateAdapter={AdapterDateFns}>
					<DatePicker
						label="End Date"
						value={epochToDate(props.endDate)}
						onChange={newValue => {
							props.setEndDate(dateToEpoch(newValue));
						}}
					/>
				</LocalizationProvider>
			</Grid>
		</Grid>
	</Box>);

export default DateFilter;
