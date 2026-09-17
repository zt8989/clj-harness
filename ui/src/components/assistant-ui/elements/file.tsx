"use client";

// LOCAL: this copied file is TRANSLATED IN PLACE. Upstream's own words -- the
// unnamed-file fallback, and the download's fallback name and its `Download <name>`
// label -- are gone from this file and read from the `elements-files` catalog instead
// (spec decision 5, which reverses flat-step-rows decision 9's "leave the copies
// untouched"). WHAT DOES NOT MOVE is the boundary: a file's name and its MIME type
// are DATA and pass through untouched; the size is `lib/format.ts`'s since ticket 02
// (see the marker below). The cost, written down: this file is no longer
// byte-comparable with upstream, so each deliberate edit below is marked `LOCAL:` --
// a marker says "this was changed on purpose", not "this is what upstream changed".
// Every non-word byte -- `data-slot`, class names, upstream identifiers -- is
// untouched.

import { memo, type FC } from "react";
import { cva, type VariantProps } from "class-variance-authority";
import {
  FileIcon,
  FileTextIcon,
  ImageIcon,
  MusicIcon,
  VideoIcon,
  BracesIcon,
  DownloadIcon,
} from "lucide-react";
import type { FileMessagePartComponent } from "@assistant-ui/react";
import { useTranslation } from "react-i18next";
// LOCAL: `formatFileSize` used to live here with its OWN rounding (one decimal).
// There were three copies of `B`/`KB`/`MB` in this UI, so the size is written by
// `lib/format.ts` now. What that costs: this row used to say "1.5 MB" and says
// "2 MB" -- the glanceable precision, the same one the sidebar's disk facts use.
// See .scratch/ui-i18n/spec.md (ticket 02).
import { formatBytes } from "@/lib/format";
import { cn } from "@/lib/utils";

const fileVariants = cva(
  "aui-file-root inline-flex items-center gap-3 rounded-lg transition-colors",
  {
    variants: {
      variant: {
        outline: "border-border hover:bg-muted/50 border",
        ghost: "hover:bg-muted/50",
        muted: "bg-muted/50 hover:bg-muted/70",
      },
      size: {
        sm: "px-2.5 py-1.5 text-xs",
        default: "px-3 py-2 text-sm",
        lg: "px-4 py-3 text-base",
      },
    },
    defaultVariants: {
      variant: "outline",
      size: "default",
    },
  },
);

function getMimeTypeIcon(mimeType: string): FC<{ className?: string }> {
  const type = mimeType.toLowerCase();
  if (type.startsWith("image/")) {
    return ImageIcon;
  }
  if (type === "application/pdf") {
    return FileTextIcon;
  }
  if (type === "application/json") {
    return BracesIcon;
  }
  if (type.startsWith("text/")) {
    return FileTextIcon;
  }
  if (type.startsWith("audio/")) {
    return MusicIcon;
  }
  if (type.startsWith("video/")) {
    return VideoIcon;
  }
  return FileIcon;
}

export type FileDataKind = "data-uri" | "url" | "base64" | "id";

function getFileDataKind(
  data: string,
  sourceType?: "url" | "id",
): FileDataKind {
  if (sourceType === "url" && /^data:/i.test(data)) return "data-uri";
  if (sourceType) return sourceType;
  if (/^data:/i.test(data)) return "data-uri";
  if (/^https?:\/\//i.test(data)) return "url";
  return "base64";
}

function getBase64Size(base64: string): number {
  const commaIndex = base64.indexOf(",");
  const base64Data = commaIndex >= 0 ? base64.slice(commaIndex + 1) : base64;
  const padding = (base64Data.match(/=/g) || []).length;
  return Math.floor((base64Data.length * 3) / 4) - padding;
}

function getDataUrlSize(data: string): number {
  const fragment = data.indexOf("#");
  const end = fragment < 0 ? data.length : fragment;
  const comma = data.indexOf(",");
  if (comma < 0 || comma >= end) {
    return 0;
  }
  let payload = data.slice(comma + 1, end);
  if (/;base64$/i.test(data.slice(0, comma))) {
    if (/[%\t\n\f\r ]/.test(payload)) {
      payload = payload
        .replace(/%([\da-f]{2})/gi, (_match, hex: string) =>
          String.fromCharCode(Number.parseInt(hex, 16)),
        )
        .replace(/[\t\n\f\r ]/g, "");
    }
    const padding = payload.endsWith("==") ? 2 : payload.endsWith("=") ? 1 : 0;
    const firstNonBase64 = payload.search(/[^A-Za-z\d+/]/);
    if (
      (firstNonBase64 !== -1 && firstNonBase64 !== payload.length - padding) ||
      payload.length % 4 === 1 ||
      (padding > 0 && payload.length % 4 !== 0)
    ) {
      return 0;
    }
    return Math.floor((payload.length * 3) / 4) - padding;
  }

  // Each percent escape is one byte, including octets that are not valid UTF-8.
  return new TextEncoder().encode(payload.replace(/%[\da-f]{2}/gi, "_"))
    .byteLength;
}

export type FileRootProps = React.ComponentProps<"div"> &
  VariantProps<typeof fileVariants>;

function FileRoot({
  className,
  variant,
  size,
  children,
  ...props
}: FileRootProps) {
  return (
    <div
      data-slot="file-root"
      data-variant={variant}
      data-size={size}
      className={cn(fileVariants({ variant, size, className }))}
      {...props}
    >
      {children}
    </div>
  );
}

type FileIconDisplayProps = React.ComponentProps<"span"> & {
  mimeType?: string;
};

function FileIconDisplay({
  mimeType,
  className,
  children,
  ...props
}: FileIconDisplayProps) {
  const IconComponent = mimeType ? getMimeTypeIcon(mimeType) : FileIcon;

  return (
    <span
      data-slot="file-icon"
      className={cn("text-muted-foreground shrink-0", className)}
      {...props}
    >
      {/* eslint-disable-next-line react-hooks/static-components -- The helper only selects module-level icon components. */}
      {children ?? <IconComponent className="size-5" />}
    </span>
  );
}

function FileName({
  className,
  children,
  ...props
}: React.ComponentProps<"span">) {
  // LOCAL: upstream's "Unnamed file" fallback is gone from this file and read from
  // the `elements-files` catalog instead. A name the part DOES carry is its own data
  // and still passes through.
  const { t } = useTranslation("elements-files");
  return (
    <span
      data-slot="file-name"
      className={cn("min-w-0 flex-1 truncate font-medium", className)}
      {...props}
    >
      {children || t("file.unnamed")}
    </span>
  );
}

type FileSizeProps = React.ComponentProps<"span"> & {
  bytes: number;
};

function FileSize({ bytes, className, ...props }: FileSizeProps) {
  return (
    <span
      data-slot="file-size"
      className={cn("text-muted-foreground shrink-0", className)}
      {...props}
    >
      {formatBytes(bytes)}
    </span>
  );
}

type FileDownloadProps = Omit<React.ComponentProps<"a">, "href"> & {
  data: string;
  mimeType: string;
  filename?: string;
  sourceType?: "url" | "id";
};

function FileDownload({
  data,
  mimeType,
  filename,
  sourceType,
  className,
  children,
  ...props
}: FileDownloadProps) {
  // LOCAL: upstream's two fallbacks -- the `download` attribute's name when the part
  // carries no filename, and the `Download <name>` label -- are gone from this file
  // and read from the `elements-files` catalog instead. The filename the part DOES
  // carry is data and still passes through.
  const { t } = useTranslation("elements-files");
  if (typeof data !== "string") return null;
  const kind = getFileDataKind(data, sourceType);
  if (kind === "id") return null;
  if (kind === "url" && !/^(https?:\/\/|blob:)/i.test(data)) return null;
  const href = kind === "base64" ? `data:${mimeType};base64,${data}` : data;

  return (
    <a
      data-slot="file-download"
      href={href}
      download={filename || t("file.downloadAttribute")}
      {...(kind === "url" && { target: "_blank", rel: "noopener noreferrer" })}
      className={cn(
        "text-muted-foreground hover:bg-accent hover:text-accent-foreground shrink-0 rounded-md p-1 transition-colors",
        className,
      )}
      aria-label={
        !children
          ? t("file.downloadLabel", {
              filename: filename || t("file.downloadFallback"),
            })
          : undefined
      }
      {...props}
    >
      {children || <DownloadIcon className="size-4" />}
    </a>
  );
}

const FileImpl: FileMessagePartComponent = ({
  filename,
  data,
  mimeType,
  sourceType,
}) => {
  const kind = getFileDataKind(data, sourceType);
  const showSize =
    typeof data === "string" && (kind === "base64" || kind === "data-uri");

  return (
    <FileRoot>
      <FileIconDisplay mimeType={mimeType} />
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <FileName>{filename}</FileName>
        {showSize && (
          <FileSize
            bytes={
              kind === "data-uri" ? getDataUrlSize(data) : getBase64Size(data)
            }
            className="text-xs"
          />
        )}
      </div>
      <FileDownload
        data={data}
        mimeType={mimeType}
        {...(filename !== undefined && { filename })}
        {...(sourceType !== undefined && { sourceType })}
      />
    </FileRoot>
  );
};

const File = memo(FileImpl) as unknown as FileMessagePartComponent & {
  Root: typeof FileRoot;
  Icon: typeof FileIconDisplay;
  Name: typeof FileName;
  Size: typeof FileSize;
  Download: typeof FileDownload;
};

File.displayName = "File";
File.Root = FileRoot;
File.Icon = FileIconDisplay;
File.Name = FileName;
File.Size = FileSize;
File.Download = FileDownload;

export {
  File,
  FileRoot,
  FileIconDisplay,
  FileName,
  FileSize,
  FileDownload,
  fileVariants,
  getMimeTypeIcon,
  getFileDataKind,
  getBase64Size,
};
