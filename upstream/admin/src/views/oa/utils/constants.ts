/** Values mirror the pinned backend OA attendance enums. Preserve their wire values. */
export const OA_ATTENDANCE_TYPE = {
  CLOCK_IN: 1,
  CLOCK_OUT: 2,
  LEAVE: 3,
  TRAVEL: 4
} as const

export const OA_ATTENDANCE_STATUS = {
  NORMAL: 1,
  LATE: 2,
  EARLY: 3,
  LEAVE: 4,
  TRAVEL: 5
} as const
