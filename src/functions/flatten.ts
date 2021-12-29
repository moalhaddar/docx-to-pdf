export const flatten: <T>(arr: T[][]) => T[] = 
	(arr) => arr.reduce((acc, currentArray) => [...acc, ...currentArray], [])