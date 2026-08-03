/**
 * 无人机相机参数数据库。
 *
 * 存储常见无人机型号的传感器尺寸和实际焦距，
 * 用于根据飞行高度计算 GSD（地面采样距离）。
 */

export interface DroneCamera {
  /** 显示名称，如 "DJI Mini 3" */
  model: string
  /** 传感器宽度（mm） */
  sensorWidth: number
  /** 实际焦距（mm），非等效焦距 */
  focalLength: number
  /** 典型图片宽度（像素），用于 FOV 估算 */
  imageWidth: number
}

/** 无人机型号数据库 */
export const DRONE_DB: DroneCamera[] = [
  { model: 'DJI Mini 3',       sensorWidth: 9.6,  focalLength: 6.72,  imageWidth: 4000  },
  { model: 'DJI Mini 4 Pro',   sensorWidth: 9.6,  focalLength: 6.72,  imageWidth: 4000  },
  { model: 'DJI Mini 2',       sensorWidth: 6.17, focalLength: 4.49,  imageWidth: 4000  },
  { model: 'DJI Air 3',        sensorWidth: 9.6,  focalLength: 6.72,  imageWidth: 4000  },
  { model: 'DJI Air 2S',       sensorWidth: 13.2, focalLength: 8.38,  imageWidth: 5472  },
  { model: 'DJI Mavic 3',      sensorWidth: 17.3, focalLength: 12.29, imageWidth: 5280  },
  { model: 'DJI Mavic 3 Pro',  sensorWidth: 17.3, focalLength: 12.29, imageWidth: 5280  },
  { model: 'DJI Mavic 2 Pro',  sensorWidth: 13.2, focalLength: 10.26, imageWidth: 5472  },
  { model: 'DJI Phantom 4',    sensorWidth: 13.2, focalLength: 8.8,   imageWidth: 4864  },
  { model: 'DJI Phantom 4 Pro',sensorWidth: 13.2, focalLength: 8.8,   imageWidth: 5472  },
  { model: 'DJI Mavic 3E',     sensorWidth: 17.3, focalLength: 12.29, imageWidth: 5280  },
  { model: 'DJI Matrice 300',  sensorWidth: 17.3, focalLength: 24.0,  imageWidth: 4056  },
  { model: 'Autel EVO II',     sensorWidth: 13.2, focalLength: 8.8,   imageWidth: 4000  },
  { model: 'Parrot Anafi',     sensorWidth: 6.17, focalLength: 4.0,   imageWidth: 4608  },
  { model: 'Skydio 2',         sensorWidth: 6.29, focalLength: 3.59,  imageWidth: 4056  },
]

/** "自定义" 选项的占位标识 */
export const CUSTOM_DRONE = '自定义'

/**
 * 根据型号名查找无人机相机参数。
 * @returns 找到的相机参数，或 null（自定义 / 未找到）
 */
export function findDrone(model: string): DroneCamera | null {
  return DRONE_DB.find(d => d.model === model) ?? null
}

/** 获取所有无人机型号名（含"自定义"） */
export function droneModelOptions(): string[] {
  return [...DRONE_DB.map(d => d.model), CUSTOM_DRONE]
}

/** 默认相机俯仰角：90°（正射，垂直向下） */
export const DEFAULT_PITCH = 90
