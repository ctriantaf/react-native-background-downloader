import DownloadTask from './DownloadTask';
import { type DownloadOptions, type SetConfigParams } from './index.d';
export declare function setConfig({ headers, progressInterval, progressMinBytes, isLogsEnabled, }: SetConfigParams): void;
export declare function checkForExistingDownloads(): Promise<DownloadTask[]>;
export declare function getExistingDownloads(): Promise<DownloadTask[]>;
export declare function ensureDownloadsAreRunning(): Promise<void>;
export declare function completeHandler(jobId: string): void;
export declare function setApproval(isApproved: boolean): void;
export declare function download(options: DownloadOptions): DownloadTask;
export declare const directories: {
    documents: string;
};
export declare const storageInfo: {
    isMMKVAvailable: boolean;
    storageType: string;
};
declare const _default: {
    download: typeof download;
    getExistingDownloads: typeof getExistingDownloads;
    checkForExistingDownloads: typeof checkForExistingDownloads;
    ensureDownloadsAreRunning: typeof ensureDownloadsAreRunning;
    completeHandler: typeof completeHandler;
    setConfig: typeof setConfig;
    setApproval: typeof setApproval;
    directories: {
        documents: string;
    };
    storageInfo: {
        isMMKVAvailable: boolean;
        storageType: string;
    };
    DownloadTask: typeof DownloadTask;
};
export default _default;
//# sourceMappingURL=index.d.ts.map