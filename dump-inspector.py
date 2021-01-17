#!/usr/bin/env python3

import argparse
import itertools
import os
import sys

from PIL import Image


def main():
    def int_0_to_100(v):
        v = int(v)
        if v < 0 or 100 < v:
            raise ValueError(f'not in range 0 to 100: {v!r}')
        return v

    parser = argparse.ArgumentParser(description='Inspect Elektro Meter dump '
                                     'file')
    parser.add_argument('input', metavar='INPUT', help='input dump file')
    parser.add_argument('--rotation', type=int, choices=[0, 90, 180, 270],
                        default=0, help='camera rotation (default: '
                        '%(default)s)')
    parser.add_argument('--blue', type=int_0_to_100, default=40,
                        help='color blue projection (default: %(default)s)')
    parser.add_argument('--red', type=int_0_to_100, default=83,
                        help='color red projection (default: %(default)s)')
    parser.add_argument('--dist', type=int_0_to_100, default=20,
                        help='color distance threshold (default: %(default)s)')
    parser.add_argument('--luma', type=int_0_to_100, default=25,
                        help='color luma threshold (default: %(default)s)')
    args = parser.parse_args()
    dump = {}
    with open(args.input, 'rb') as f:
        for k in ['format', 'width', 'height', *(
                f'{a}_{b}' for a, b in itertools.product(
                    'yuv', ['row_stride', 'pixel_stride', 'length']))]:
            t, v = f.readline().decode().split(':', maxsplit=1)
            v = v.rstrip('\n')
            if t != k:
                raise Exception(f'unexpected key: {t!r}')
            if k == 'format' and v != 'YUV_420_888':
                raise Exception(f'unsupported format: {v!r}')
            dump[k] = v if k == 'format' else int(v)
        for k in 'yuv':
            dump[f'{k}_buffer'] = f.read(dump[f'{k}_length'])
    width, height = dump['width'], dump['height']
    im_yuv = Image.new('RGB', (width, height))
    im_color = Image.new('RGB', (width, height))
    im_mask = Image.new('RGB', (width, height))
    im_dist = Image.new('RGB', (width, height))
    color_luma_threshold = args.luma*255//100
    color_blue_projection = args.blue*255//100
    color_red_projection = args.red*255//100
    color_distance_threshold = args.dist*255//100
    color_distance_threshold_squared = color_distance_threshold**2
    for y, x in itertools.product(range(height), range(width)):
        pixelY = dump['y_buffer'][y*dump['y_row_stride'] +
                                  x*dump['y_pixel_stride']]
        pixelU = dump['u_buffer'][(y//2)*dump['u_row_stride'] +
                                  (x//2)*dump['u_pixel_stride']]
        pixelV = dump['v_buffer'][(y//2)*dump['v_row_stride'] +
                                  (x//2)*dump['v_pixel_stride']]
        im_yuv.putpixel((x, y), (pixelV, pixelY, pixelU))
        diffU = color_blue_projection - pixelU
        diffV = color_red_projection - pixelV
        distance_squared = diffU * diffU + diffV * diffV
        distance_color = min(255, round(distance_squared**0.5))
        pixelY1 = ((19077 << 8) * pixelY) >> 16
        pixelR = (pixelY1 + (((26149 << 8) * pixelV) >> 16) - 14234) >> 6
        pixelG = (pixelY1 - (((6419 << 8) * pixelU) >> 16) -
                  (((13320 << 8) * pixelV) >> 16) + 8708) >> 6
        pixelB = (pixelY1 + (((33050 << 8) * pixelU) >> 16) - 17685) >> 6
        pixelR = max(0, min(255, pixelR))
        pixelG = max(0, min(255, pixelG))
        pixelB = max(0, min(255, pixelB))
        im_color.putpixel((x, y), (pixelR, pixelG, pixelB))
        im_dist.putpixel((x, y), (distance_color,)*3)
        if (pixelY >= color_luma_threshold and
                distance_squared <= color_distance_threshold_squared):
            im_mask.putpixel((x, y), (pixelR, pixelG, pixelB))
    for im, name in [(im_yuv, 'yuv'), (im_color, 'color'), (im_dist, 'dist'),
                     (im_mask, 'mask')]:
        path = f'{os.path.splitext(args.input)[0]}.{name}.png'
        print(f'Saving {path}', file=sys.stderr)
        im.rotate(-args.rotation, expand=True).save(path)
        if name == 'yuv':
            print('    Channel Mapping:\n'
                  '      * R: Red Projection (V)\n'
                  '      * G: Luma (Y)\n'
                  '      * B: Blue Projection (U)', file=sys.stderr)


if __name__ == '__main__':
    main()
