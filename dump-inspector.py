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
    im_vyu = Image.open(args.input).convert('RGB')
    width, height = im_vyu.size
    im_color = Image.new('RGB', (width, height))
    im_mask = Image.new('RGB', (width, height))
    im_dist = Image.new('RGB', (width, height))
    luma_threshold = args.luma*255//100
    blue_projection = args.blue*255//100
    red_projection = args.red*255//100
    distance_threshold = args.dist*255//100
    distance_threshold_squared = distance_threshold**2
    for y, x in itertools.product(range(height), range(width)):
        pixel_v, pixel_y, pixel_u = im_vyu.getpixel((x, y))
        # Convert from JPEG YUV to RGB color space using 16.16 fixed point math
        pixel_y1 = 65536 * pixel_y
        pixel_r = max(0, min(255, pixel_y1 + 91881*pixel_v - 11760828 >> 16))
        pixel_g = max(0, min(255, (pixel_y1 - 22553*pixel_u - 46802*pixel_v
                                   + 8877429 >> 16)))
        pixel_b = max(0, min(255, pixel_y1 + 116130*pixel_u - 14864613 >> 16))
        im_color.putpixel((x, y), (pixel_r, pixel_g, pixel_b))
        diff_u = blue_projection - pixel_u
        diff_v = red_projection - pixel_v
        distance_squared = diff_u**2 + diff_v**2
        distance_gray = min(255, round(distance_squared**0.5))
        im_dist.putpixel((x, y), (distance_gray,)*3)
        if (pixel_y >= luma_threshold
                and distance_squared <= distance_threshold_squared):
            im_mask.putpixel((x, y), (pixel_r, pixel_g, pixel_b))
    for im, name in [(im_vyu, 'vyu'), (im_color, 'color'), (im_dist, 'dist'),
                     (im_mask, 'mask')]:
        path = f'{os.path.splitext(args.input)[0]}.{name}.png'
        print(f'Saving {path}', file=sys.stderr)
        im.rotate(-args.rotation, expand=True).save(path)
        if name == 'vyu':
            with open(path.removesuffix('.png') + '.txt', 'w') as f:
                f.write('Channel Mapping:\n'
                        '* R: Red Projection (V)\n'
                        '* G: Luma (Y)\n'
                        '* B: Blue Projection (U)\n')


if __name__ == '__main__':
    main()
